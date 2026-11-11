# Arquitetura

O ambiente serve para projetar soluções serverless orquestradas pelo AWS Step Functions, com
Lambdas em Java 21 e Spring Boot 4.0. O deploy usa o Serverless Framework v4 e, localmente, o
[Floci](https://github.com/floci-io/floci) emula os serviços da AWS na porta 4566.

A `sample-solution` é a solução de referência: recebe uma requisição HTTP, valida, persiste e
notifica, com falhas enviadas para uma fila de mensagens com falha (DLQ) e reprocessamento agendado.
O passo a passo de cada caminho está em [Fluxos](flows.md).

## Contexto

```mermaid
flowchart LR
    client([Cliente HTTP])
    dev([Desenvolvedor])
    subgraph solution[sample-solution]
        api[API HTTP]
        flow[Fluxo de processamento]
    end
    aws[(Serviços AWS ou Floci)]
    client -->|POST /requests| api
    api --> flow
    solution -->|AWS SDK v2| aws
    dev -->|pnpm sls deploy| aws
```

## Containers

```mermaid
flowchart TB
    client([Cliente HTTP]) -->|POST /requests| apigw[API Gateway REST]
    apigw --> apiFn[Lambda api - Spring MVC]
    apiFn -->|PutObject| s3[(S3 payloads)]
    apiFn -->|StartExecution| sfn{{Step Functions request-flow}}
    sfn --> validate[Lambda validate]
    sfn --> persist[Lambda persist]
    sfn --> notify[Lambda notify]
    sfn -->|em caso de falha| dlq[[SQS dlq]]
    validate --> ssm[(SSM max-payload-bytes)]
    validate --> secrets[(Secrets Manager signing-key)]
    validate -->|GetObject| s3
    persist -->|PutItem| ddb[(DynamoDB requests)]
    notify -->|Publish| sns[[SNS notifications]]
    notify -->|PutEvents| bus[[EventBridge events]]
    bus -->|regra RequestCompleted| audit[[SQS audit]]
    scheduler[EventBridge Scheduler - a cada 5 min] --> reprocess[Lambda reprocess]
    reprocess -->|ReceiveMessage e DeleteMessage| dlq
    reprocess -->|StartExecution| sfn
```

## Módulos Maven

| Módulo | Empacotamento | Responsabilidade |
|---|---|---|
| `serverless-parent` (raiz) | `pom` | Herda do Spring Boot 4.0.9; importa os BOMs do Spring Cloud 2025.1.4 e do AWS SDK 2.57.12; exclui `apache-client` e `netty-nio-client` de cada service do SDK |
| `shared` | `jar` | Autoconfiguração dos clients síncronos do SDK, cada um com o próprio `AwsCrtHttpClient` e criado só quando o SDK correspondente está no classpath ([ADR 0005](decisions/0005-clients-sincronos-com-crt.md)) |
| `sample-solution` | `pom` | Agrega `api` e `functions` e define o empacotamento em zip para Lambda |
| `sample-solution/api` | `jar` + zip | Lambda HTTP com Spring MVC via `aws-serverless-java-container-springboot4` |
| `sample-solution/functions` | `jar` + zip | Lambdas dos passos e do reprocessamento com Spring Cloud Function |
| `sample-solution/e2e` | `jar`, só com testes | Testes ponta a ponta contra a stack implantada; entra no build apenas com o perfil `-Pe2e` ([Testes](testing.md)) |

## Lambdas

| Função | Handler | Gatilho | SDKs usados | Entrada e saída |
|---|---|---|---|---|
| `api` | `com.mmda.serverless.sample.api.StreamLambdaHandler::handleRequest` | API Gateway, `POST /requests` | S3, Step Functions | Evento proxy do API Gateway; responde `202` ([OpenAPI](api/openapi.yaml)) |
| `validate` | `org.springframework.cloud.function.adapter.aws.FunctionInvoker::handleRequest` | Step Functions | S3, SSM, Secrets Manager | `RequestContext` → `RequestContext` com `signature` e `payloadBytes` |
| `persist` | `FunctionInvoker` | Step Functions | DynamoDB | `RequestContext` → `RequestContext` |
| `notify` | `FunctionInvoker` | Step Functions | SNS, EventBridge | `RequestContext` → `RequestContext` |
| `reprocess` | `FunctionInvoker` | EventBridge Scheduler, `rate(5 minutes)` | SQS, Step Functions | `ReprocessCommand` → `ReprocessResult` |

As quatro funções do módulo `functions` usam o mesmo zip. Cada Lambda escolhe a sua pela variável
`SPRING_CLOUD_FUNCTION_DEFINITION`, e o `FunctionInvoker` encontra a aplicação pela variável
`MAIN_CLASS`. Os formatos de `RequestContext`, `ReprocessCommand` e `ReprocessResult` estão no
[AsyncAPI](events/asyncapi.yaml).

## Recursos AWS

Declarados em `sample-solution/serverless.yml`. Os nomes seguem o padrão
`sample-solution-<stage>-<sufixo>`; no stage `local`, `sample-solution-local-payloads` e assim por diante.

| Recurso lógico | Tipo | Sufixo do nome | Uso |
|---|---|---|---|
| `PayloadBucket` | `AWS::S3::Bucket` | `payloads` | Payload original de cada requisição, em `requests/<requestId>.json` |
| `RequestsTable` | `AWS::DynamoDB::Table` | `requests` | Resultado da validação; chave `requestId` (texto), cobrança por requisição |
| `RequestFlowStateMachine` | `AWS::StepFunctions::StateMachine` | `request-flow` | Orquestra `validate`, `persist` e `notify` |
| `DeadLetterQueue` | `AWS::SQS::Queue` | `dlq` | Execuções que falharam, com o erro em `error` |
| `AuditQueue` + `AuditQueuePolicy` | `AWS::SQS::Queue` | `audit` | Recebe o evento `RequestCompleted` pela regra do EventBridge |
| `NotificationsTopic` | `AWS::SNS::Topic` | `notifications` | Notificação de requisição concluída (sem assinantes no exemplo) |
| `RequestsEventBus` + `RequestCompletedRule` | `AWS::Events::EventBus` / `Rule` | `events` | Barramento próprio; a regra filtra `source = mmda.sample` e `detail-type = RequestCompleted` |
| `MaxPayloadParameter` | `AWS::SSM::Parameter` | `/sample-solution/<stage>/max-payload-bytes` | Limite de tamanho do payload, `4096` bytes |
| `SigningSecret` | `AWS::SecretsManager::Secret` | `sample-solution/<stage>/signing-key` | Chave HMAC gerada pelo próprio Secrets Manager (32 caracteres) |
| Agendamento do `reprocess` | `AWS::Scheduler::Schedule` | gerado pelo Serverless | Aciona o `reprocess` a cada 5 minutos com `{"maxMessages":10}` |

## Configuração

Todas as Lambdas recebem as variáveis de `provider.environment`. A aplicação Spring as lê pelo
`application.yml` de cada módulo.

| Variável | Valor no stage `local` | Usada por |
|---|---|---|
| `AWS_ENDPOINT_URL` | `http://floci:4566` | Todos os clients do SDK |
| `AWS_ENDPOINT_URL_S3` | `http://floci:4566` | Client do S3 ([ADR 0008](decisions/0008-aws-endpoint-url-s3-no-offline.md)) |
| `S3_PATH_STYLE_ACCESS` | `true` | Client do S3 (`mmda.aws.s3.path-style-access`) |
| `BUCKET_NAME` | `sample-solution-local-payloads` | `api`, `validate` |
| `TABLE_NAME` | `sample-solution-local-requests` | `persist` |
| `TOPIC_ARN` | `arn:aws:sns:<região>:<conta>:sample-solution-local-notifications` | `notify` |
| `EVENT_BUS_NAME` | `sample-solution-local-events` | `notify` |
| `DEAD_LETTER_QUEUE_NAME` | `sample-solution-local-dlq` | `reprocess` |
| `STATE_MACHINE_ARN` | `arn:aws:states:<região>:<conta>:stateMachine:sample-solution-local-request-flow` | `api`, `reprocess` |
| `MAX_PAYLOAD_PARAMETER` | `/sample-solution/local/max-payload-bytes` | `validate` |
| `SIGNING_SECRET_ID` | `sample-solution/local/signing-key` | `validate` |
| `MAX_ATTEMPTS` | não definida; padrão `3` | `reprocess` |
| `MAIN_CLASS` / `SPRING_CLOUD_FUNCTION_DEFINITION` | por função | Lambdas do módulo `functions` |

Região, conta e endpoint vêm das cadeias padrão do AWS SDK, então o mesmo código roda no Floci e na
AWS. Os valores de `AWS_ENDPOINT_URL*` e `S3_PATH_STYLE_ACCESS` estão em `params.local` e existem só
para o stage `local`.

## Empacotamento

O `mvn package` gera um zip por módulo no formato que o runtime `java21` da Lambda carrega: classes
na raiz e dependências em `lib/`. Não há jar executável do Spring Boot.

```text
api-lambda.zip / functions-lambda.zip
├── com/mmda/serverless/...     classes compiladas
├── application.yml
└── lib/                        dependências de runtime
```

## Ambiente local

```mermaid
flowchart LR
    dev([Desenvolvedor]) -->|pnpm sls deploy --stage local| sls[Serverless Framework v4 e serverless-localstack]
    sls -->|CloudFormation em localhost:4566| floci[Floci]
    floci -->|socket do Docker| lambdas[Containers das Lambdas - imagem java 21]
    lambdas -->|AWS_ENDPOINT_URL floci:4566| floci
    dev -->|pnpm sls offline --stage local| offline[serverless-offline na porta 3000]
    offline -->|container na rede floci| apiC[Lambda api]
    apiC -->|floci:4566| floci
```

- O Floci cria um container por Lambda a partir da imagem oficial `public.ecr.aws/lambda/java:21` e
  injeta `AWS_ENDPOINT_URL=http://floci:4566` por causa de `FLOCI_HOSTNAME=floci` no `compose.yaml`.
- O `serverless-offline` executa só as Lambdas e a rota HTTP; a máquina de estado e os recursos
  continuam no Floci, então ele depende do deploy local.
- Decisão sobre o emulador e os plugins: [ADR 0001](decisions/0001-serverless-framework-e-floci.md).

## Permissões

A role das Lambdas (`provider.iam.role.statements`) concede só o necessário ao fluxo:
`s3:GetObject` e `s3:PutObject` no bucket de payloads, `dynamodb:PutItem` na tabela, `sns:Publish` no
tópico, `events:PutEvents` no barramento, `ssm:GetParameter` no parâmetro, `secretsmanager:GetSecretValue`
no segredo, `sqs:GetQueueUrl`, `sqs:ReceiveMessage` e `sqs:DeleteMessage` na DLQ e
`states:StartExecution` na máquina de estado. O ARN da máquina é montado por nome: um `Ref` criaria
dependência circular com as Lambdas que ela invoca. O Floci não aplica essas políticas por padrão.
