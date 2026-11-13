# sample-solution

Solução de referência em Spring: recebe um payload JSON por HTTP, guarda no S3 e processa numa máquina de
estado (validar, persistir e notificar), com as falhas numa fila de mensagens com falha (DLQ) e
reprocessamento agendado. O `RequestCompleted` que ela emite aciona a
[`receipt-solution`](../../receipt-solution/docs/README.md). O desenho de contexto está em
[Arquitetura](../../docs/arquitetura.md), o passo a passo dos caminhos em [Fluxos](../../docs/fluxos.md), a
subida de cada Lambda no [Guia do código](../../docs/guia-do-codigo.md) e as decisões em
[`docs/decisoes/`](../../docs/decisoes). Parent, biblioteca de clients e barramentos estão em
[Plataforma](../../platform/docs/README.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Funções Lambda e papéis](#funções-lambda-e-papéis)
- [Máquina de estado](#máquina-de-estado)
- [API HTTP](#api-http)
- [Eventos publicados e consumidos](#eventos-publicados-e-consumidos)
- [Persistência](#persistência)
- [Filas e DLQ](#filas-e-dlq)
- [Segurança e IAM](#segurança-e-iam)
- [Observabilidade e alarmes](#observabilidade-e-alarmes)
- [Configuração](#configuração)
- [Rodar localmente no Floci](#rodar-localmente-no-floci)
- [Implantar na AWS](#implantar-na-aws)
- [Testes](#testes)
- [Diagramas](#diagramas)
- [Divergências conhecidas](#divergências-conhecidas)

## Propósito e responsabilidades

| Responsabilidade | Onde fica |
|---|---|
| Aceitar o payload e responder `202` sem esperar o processamento | [`RequestController`](../api/src/main/java/com/mmda/serverless/sample/api/RequestController.java) |
| Guardar o payload no S3 e passar só a chave pela máquina de estado ([ADR 0006](../../docs/decisoes/0006-payload-no-s3.md)) | `RequestController`, objeto `requests/<requestId>.json` |
| Rejeitar payload vazio ou acima do limite e assinar o conteúdo com HMAC-SHA256 | [`ValidateFunction`](../functions/src/main/java/com/mmda/serverless/sample/functions/ValidateFunction.java) |
| Gravar o resultado da validação | [`PersistFunction`](../functions/src/main/java/com/mmda/serverless/sample/functions/PersistFunction.java) |
| Publicar no SNS e emitir `RequestCompleted` no barramento próprio | [`NotifyFunction`](../functions/src/main/java/com/mmda/serverless/sample/functions/NotifyFunction.java) |
| Levar qualquer falha de passo para a DLQ | Estado `SendToDeadLetterQueue` da `request-flow`, em [`serverless.yml`](../serverless.yml) |
| Reiniciar o que está na DLQ até `maxAttempts` e descartar depois ([ADR 0007](../../docs/decisoes/0007-dlq-e-reprocessamento.md)) | [`ReprocessFunction`](../functions/src/main/java/com/mmda/serverless/sample/functions/ReprocessFunction.java) |

A solução não conhece quem consome o `RequestCompleted`: a regra da `receipt-solution` mora no barramento
desta, mas é declarada e implantada pela consumidora
([ADR 0011](../../docs/decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)).

## Funções Lambda e papéis

Todas rodam no runtime `java21`, com 1769 MB (equivale a 1 vCPU). Os handlers, os SDKs usados e a entrada e
a saída de cada função estão em [Arquitetura § Lambdas](../../docs/arquitetura.md#lambdas).

| Função (`sample-solution-<stage>-…`) | Pacote | Timeout | Gatilho | Papel |
|---|---|---|---|---|
| `api` | `api/target/api-lambda.zip` | 29 s | API Gateway, `POST /requests` | Entrada HTTP |
| `validate` | `functions/target/functions-lambda.zip` | 60 s | Estado `Validate` | Passo de negócio |
| `persist` | mesmo zip | 60 s | Estado `Persist` | Passo de negócio |
| `notify` | mesmo zip | 60 s | Estado `Notify` | Passo de negócio e publicação |
| `reprocess` | mesmo zip | 60 s | EventBridge Scheduler, `rate(5 minutes)`, entrada `{"maxMessages":10}` | Operação da DLQ |

As quatro funções do módulo `functions` dividem o zip; cada uma escolhe o bean pela variável
`SPRING_CLOUD_FUNCTION_DEFINITION`, e o `FunctionInvoker` sobe a `FunctionsApplication` indicada em
`MAIN_CLASS`. O `spring.main.lazy-initialization: true` evita criar os beans e os clients das outras funções
([Guia do código](../../docs/guia-do-codigo.md#como-as-lambdas-dos-passos-sobem-exemplo-validate)).

## Máquina de estado

`sample-solution-<stage>-request-flow` (Standard), definida em `stepFunctions.stateMachines.requestFlow`:
`Validate`, `Persist` e `Notify`, cada um com `Catch` de `States.ALL` que grava o erro em `$.error` e segue
para `SendToDeadLetterQueue` (integração direta `sqs:sendMessage`) e `RequestFailed` (`Fail`). Nenhum estado
tem `Retry`: a nova tentativa vem do `reprocess`. A tabela dos estados está em
[Fluxos § Máquina de estado](../../docs/fluxos.md#máquina-de-estado-request-flow) e o desenho em
[`estados-request-flow.puml`](diagramas/estados-request-flow.puml).

O nome de cada execução é o `requestId` na primeira tentativa e `requestId-<attempt>` nas seguintes, o que
liga a execução ao objeto no S3 e ao item da tabela.

## API HTTP

Contrato: [`contracts/sample-solution/openapi.yaml`](../../contracts/sample-solution/openapi.yaml) (OpenAPI 3.1),
conferido pelo `RequestControllerContractTest`. API REST `<stage>-sample-solution`, sem autorizador.

| Método | Caminho | Lambda | Sucesso | Falhas |
|---|---|---|---|---|
| `POST` | `/requests` | `api` (`RequestController.create`) | `202` com `requestId` e `executionArn` | `415` (Content-Type diferente de `application/json`), `400` (corpo ausente), `500` (falha no S3 ou no `StartExecution`) |

O corpo chega ao controller como texto, sem conferência de que é JSON válido; o tamanho só é conferido depois, no `validate`. Um método não mapeado recebe `403`
do próprio API Gateway. Onde cada erro termina: [Fluxos § Onde cada erro termina](../../docs/fluxos.md#onde-cada-erro-termina).

## Eventos publicados e consumidos

Formatos no [AsyncAPI da sample](../../contracts/sample-solution/asyncapi.yaml).

| Mensagem | Canal | Produtor | Consumidor | Quando |
|---|---|---|---|---|
| `RequestCompleted` (`source` `mmda.sample`) | EventBridge `sample-solution-<stage>-events` | `notify` | Fila `audit` (`RequestCompletedRule`) e `receipt-flow` (regra da `receipt-solution`) | Fim do `Notify`, com o `RequestContext` no `detail` |
| Notificação de conclusão | SNS `sample-solution-<stage>-notifications` | `notify` | Ninguém no exemplo | Logo antes do `RequestCompleted` |
| Falha de passo | SQS `sample-solution-<stage>-dlq` | Step Functions (`SendToDeadLetterQueue`) | `reprocess` | `Catch` de qualquer passo |
| Comando de reprocessamento `{"maxMessages":n}` | Invocação da Lambda `reprocess` | EventBridge Scheduler ou o desenvolvedor | `reprocess` | A cada 5 minutos ou sob demanda |

A solução não consome evento de outra. O `NotifyFunction` lança exceção quando o `PutEvents` volta com
`FailedEntryCount > 0`, e a execução vai para a DLQ.

## Persistência

| Recurso | Nome | Chave | Atributos | Escrita | Leitura |
|---|---|---|---|---|---|
| DynamoDB | `sample-solution-<stage>-requests` (`PAY_PER_REQUEST`) | `requestId` (S, HASH) | `s3Key` (S), `signature` (S), `payloadBytes` (N), `attempt` (N), `persistedAt` (S) | `persist`, `PutItem` sem condição | Nenhuma função; só consulta manual e testes |
| S3 | `sample-solution-<stage>-payloads` | `requests/<requestId>.json` | Corpo recebido, `Content-Type: application/json` | `api`, `PutObject` | `validate`, `GetObject` |
| SSM Parameter Store | `/sample-solution/<stage>/max-payload-bytes` | — | `4096` | CloudFormation | `validate` |
| Secrets Manager | `sample-solution/<stage>/signing-key` | — | Chave de 32 caracteres sem pontuação, gerada pelo Secrets Manager | CloudFormation | `validate` |

A tabela não tem índice, stream nem TTL, e o código nunca apaga o objeto do S3. Modelo em
[`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml).

## Filas e DLQ

| Fila | Origem | Consumidor | Configuração |
|---|---|---|---|
| `sample-solution-<stage>-dlq` | `SendToDeadLetterQueue` da `request-flow` | `reprocess` (`ReceiveMessage` de até 10 mensagens e `DeleteMessage` de cada uma) | Padrões do SQS: sem `RedrivePolicy`, retenção e visibilidade não declaradas |
| `sample-solution-<stage>-audit` | `RequestCompletedRule` (`AuditQueuePolicy` só aceita essa regra como `aws:SourceArn`) | Ninguém no exemplo; usada pelos testes | Padrões do SQS |

O `reprocess` reinicia a mensagem com `attempt + 1` enquanto `attempt < maxAttempts` (`MAX_ATTEMPTS`, padrão
`3`) e descarta depois, com um `WARN` no log. Um payload que sempre falha roda as tentativas 1, 2 e 3 e é
descartado ([Fluxos § Reprocessamento](../../docs/fluxos.md#reprocessamento)). Para reprocessar sem esperar o
agendamento, use o `lambda invoke` de [README § Fluxo de exemplo](../../README.md#fluxo-de-exemplo).

## Segurança e IAM

- **API:** sem autorizador nem chave de API; quem tem a URL pode chamar o `POST /requests`.
- **Papel das Lambdas:** um só papel para as cinco funções, gerado pelo Serverless a partir de
  `provider.iam.role.statements`. Cada função recebe todas as permissões abaixo, inclusive as que não usa.
  Para comparar: a `quote-solution` tem um papel por função, e a `provider-solution` separa os da API e dos
  simuladores ([desenho técnico § 2](../../docs/plataforma-de-cotacao.md#2-recursos-por-stack)).
- **Papéis gerados por plugin:** o da `request-flow` vem do `serverless-step-functions`; o do agendamento,
  do Serverless.
- **Floci:** não aplica as políticas por padrão; elas só valem de fato na AWS.

| Ação | Recurso | Quem usa |
|---|---|---|
| `s3:GetObject`, `s3:PutObject` | `arn:aws:s3:::sample-solution-<stage>-payloads/*` | `api` (Put), `validate` (Get) |
| `dynamodb:PutItem` | Tabela `requests` | `persist` |
| `sns:Publish` | Tópico `notifications` | `notify` |
| `events:PutEvents` | Barramento `events` | `notify` |
| `ssm:GetParameter` | Parâmetro `max-payload-bytes` | `validate` |
| `secretsmanager:GetSecretValue` | Segredo `signing-key` | `validate` |
| `sqs:GetQueueUrl`, `sqs:ReceiveMessage`, `sqs:DeleteMessage` | Fila `dlq` | `reprocess` |
| `states:StartExecution` | ARN da `request-flow` montado por nome (um `Ref` criaria ciclo com as Lambdas) | `api`, `reprocess` |

O bucket não declara criptografia nem bloqueio de acesso público; valem os padrões da AWS para buckets novos.
Nenhum evento ou log leva a chave de assinatura.

## Observabilidade e alarmes

A solução não declara alarmes, métricas próprias nem rastreamento (X-Ray), e o `serverless.yml` não define
`logRetentionInDays`.

| Sinal | Onde ver |
|---|---|
| Execução `FAILED` com erro `RequestFailed` | Histórico da execução `request-flow` (Step Functions ou console do Floci em `localhost:4500`) |
| Mensagem parada na DLQ | Fila `dlq`; nenhum alarme avisa |
| Descarte depois de `maxAttempts` | Log da `reprocess`: `Discarding request <id> after <n> attempts` |
| Resultado de uma rodada do `reprocess` | Retorno `{restarted, discarded}` da invocação |
| Logs das funções | `/aws/lambda/sample-solution-<stage>-<função>` |

## Configuração

Os `params` existem só para o stage `local`; o valor de cada variável no Floci está em
[Arquitetura § Configuração](../../docs/arquitetura.md#configuração).

| Param (`params.local`) | Valor | Variável |
|---|---|---|
| `awsEndpointUrl` | `http://floci:4566` | `AWS_ENDPOINT_URL` e `AWS_ENDPOINT_URL_S3` ([ADR 0008](../../docs/decisoes/0008-aws-endpoint-url-s3-no-offline.md)) |
| `s3PathStyleAccess` | `"true"` | `S3_PATH_STYLE_ACCESS` |

| Variável | Propriedade | Padrão no código | Usada por |
|---|---|---|---|
| `BUCKET_NAME` | `sample.bucket-name` | obrigatória | `api`, `validate` |
| `STATE_MACHINE_ARN` | `sample.state-machine-arn` | obrigatória | `api`, `reprocess` |
| `TABLE_NAME` | `sample.table-name` | obrigatória | `persist` |
| `TOPIC_ARN` | `sample.topic-arn` | obrigatória | `notify` |
| `EVENT_BUS_NAME` | `sample.event-bus-name` | obrigatória | `notify` |
| `DEAD_LETTER_QUEUE_NAME` | `sample.dead-letter-queue-name` | obrigatória | `reprocess` |
| `MAX_PAYLOAD_PARAMETER` | `sample.max-payload-parameter` | obrigatória | `validate` |
| `SIGNING_SECRET_ID` | `sample.signing-secret-id` | obrigatória | `validate` |
| `MAX_ATTEMPTS` | `sample.max-attempts` | `3` (não definida no `serverless.yml`) | `reprocess` |
| `S3_PATH_STYLE_ACCESS` | `mmda.aws.s3.path-style-access` | `false` | Client do S3 do `spring-aws` |

As propriedades `sample.*` ficam em `ApiProperties` e `FunctionsProperties` (`@ConfigurationProperties`), lidas
do `application.yml` de cada módulo. Região, credenciais e endpoint seguem as cadeias padrão do SDK.

## Rodar localmente no Floci

Com o Floci no ar (`docker compose up -d`) e `pnpm install` feito na raiz:

```bash
mvn -B -q package -DskipTests -pl sample-solution/api,sample-solution/functions -am
pnpm deploy:sample
```

A `sample-solution` não depende do barramento da plataforma, então pode subir antes ou depois de
`deploy:platform`; a `receipt-solution` precisa dela antes. A ordem completa e o `deploy:local` estão em
[README § Implantação no Floci](../../README.md#implantação-no-floci). Os `curl` dos caminhos de sucesso e de
falha, o `serverless-offline` e a remoção estão no mesmo README, em
[Fluxo de exemplo](../../README.md#fluxo-de-exemplo),
[Rodar a API com o serverless-offline](../../README.md#rodar-a-api-com-o-serverless-offline) e
[Remover a implantação do Floci](../../README.md#remover-a-implantação-do-floci).

## Implantar na AWS

Não preparado. O [guia do stage `dev`](../../docs/implantacao-aws-dev.md) cobre só a plataforma de cotação.
Antes de um `sls deploy --stage <stage>` numa conta AWS, falta:

1. Declarar `params` para o stage: hoje só há `params.local`, então `${param:awsEndpointUrl}` e
   `${param:s3PathStyleAccess}` ficam sem valor em outro stage. A quote e a provider resolvem isso com
   `params.default` e as variáveis de endpoint condicionadas a `HasAwsEndpointUrl`
   ([parâmetros por stage](../../docs/plataforma-de-cotacao.md#12-parâmetros-por-stage)).
2. Conferir a disponibilidade do nome do bucket `sample-solution-<stage>-payloads`, que é global no S3.
3. Implantar a `receipt-solution` só depois desta, se ela for junto.

A topologia alvo está em [`implantacao-aws.puml`](diagramas/implantacao-aws.puml); nada dela foi executado na
AWS.

## Testes

Unitários e de contrato no `api` e no `functions` (`*Test`, `mvn test`), integração com o Floci
(`RequestFlowFunctionsIT` e `RequestControllerFlociIT`, `mvn verify`) e ponta a ponta (`RequestFlowE2E`, perfil
`-Pe2e`, contra a stack implantada). Para rodar só esta solução:

```bash
mvn -B verify -pl sample-solution/api,sample-solution/functions -am
```

O que cada classe cobre e os limites conhecidos estão em [Testes](../../docs/testes.md): seções
[Unitários](../../docs/testes.md#unitários), [Contrato](../../docs/testes.md#contrato),
[Integração com Floci](../../docs/testes.md#integração-com-floci),
[Ponta a ponta](../../docs/testes.md#ponta-a-ponta) e [Limites conhecidos](../../docs/testes.md#limites-conhecidos).

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas), com o tema único
[`tema-plataforma.iuml`](../../docs/diagramas/tema-plataforma.iuml). Para renderizar só esta solução, na raiz:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/sample-solution 'sample-solution/docs/diagramas/*.puml'
```

| Diagrama | O que mostra e como ler |
|---|---|
| [`c4-conteiner.puml`](diagramas/c4-conteiner.puml) | Contêineres da solução (C4 nível 2): API, Lambdas, máquina de estado, dados e mensageria. Azul escuro é Lambda; tracejado roxo é assíncrono; tracejado vermelho é o caminho de falha e o reprocessamento |
| [`c4-componente-api.puml`](diagramas/c4-componente-api.puml) | O que roda dentro da Lambda `api`: handler estático, container do Spring, controller e clients do `spring-aws` |
| [`c4-componente-functions.puml`](diagramas/c4-componente-functions.puml) | O zip compartilhado pelas quatro funções: o `FunctionInvoker` escolhe o bean, e cada bean fala com os seus recursos |
| [`sequencia-requisicao.puml`](diagramas/sequencia-requisicao.puml) | `POST /requests` até o `RequestCompleted`: a parte síncrona termina no `202`, a assíncrona segue na `request-flow` |
| [`sequencia-falha.puml`](diagramas/sequencia-falha.puml) | Erro num passo, `Catch` para a DLQ e fim em `RequestFailed`; mostra o que vai no corpo da mensagem |
| [`sequencia-reprocessamento.puml`](diagramas/sequencia-reprocessamento.puml) | Rodada do `reprocess`: lote, reinício com `attempt + 1` ou descarte, e `DeleteMessage` de cada mensagem |
| [`estados-request-flow.puml`](diagramas/estados-request-flow.puml) | Estados da máquina; setas vermelhas são o `Catch` |
| [`estados-requisicao.puml`](diagramas/estados-requisicao.puml) | Ciclo de vida de uma requisição entre tentativas (não há enum no código); seta azul tracejada é o reprocessamento |
| [`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml) | Item da tabela `requests` e objeto do S3 que ele referencia por `s3Key` |
| [`implantacao-floci.puml`](diagramas/implantacao-floci.puml) | Stage `local`: Serverless, Floci, console e contêineres das Lambdas criados pelo Floci |
| [`implantacao-aws.puml`](diagramas/implantacao-aws.puml) | Topologia alvo na AWS, com o que falta preparar em vermelho |
| [`classes-api.puml`](diagramas/classes-api.puml) | Classes do pacote `com.mmda.serverless.sample.api` |
| [`classes-functions.puml`](diagramas/classes-functions.puml) | Classes do pacote `com.mmda.serverless.sample.functions`: funções, records e exceção |
| [`casos-de-uso.puml`](diagramas/casos-de-uso.puml) | Atores (cliente, desenvolvedor, Scheduler) e o que cada um aciona |

## Divergências conhecidas

1. **Ordem do deploy em Arquitetura.** O diagrama de ambiente local de
   [`arquitetura.md`](../../docs/arquitetura.md#ambiente-local) diz "sample e depois receipt", mas o
   `deploy:local` do `package.json` implanta cinco stacks: plataforma, sample, receipt, provider e quote.
2. **Caminho do tema.** O [`docs/README.md`](../../docs/README.md#diagramas) e o cabeçalho do tema indicam
   `!include ../../docs/diagramas/tema-plataforma.iuml` para `<solução>/docs/diagramas/`; esse caminho não
   renderiza. O certo, usado aqui, é `../../../docs/diagramas/tema-plataforma.iuml`.
3. **Comando de renderização.** O comando de [`docs/README.md`](../../docs/README.md#diagramas) grava tudo em
   `build/diagramas/`, e diagramas de mesmo nome em soluções diferentes (como `c4-conteiner.puml`) se
   sobrescrevem. O comando da seção [Diagramas](#diagramas) grava numa pasta por solução.
4. **Repetição depois do `Persist`.** Se o `Notify` falhar depois do `Persist`, a tentativa seguinte
   regrava o item (o `PutItem` não tem condição) e o SNS pode receber a mesma requisição de novo. O
   `RequestCompleted` só sai quando o `Notify` termina sem erro.
5. **`StartExecution` que falha no `reprocess`.** O `DeleteMessage` vem depois do `StartExecution`: se o
   reinício falhar, por exemplo com `ExecutionAlreadyExists` numa mensagem entregue duas vezes depois de a
   execução `requestId-<attempt>` terminar, a rodada para com erro, a mensagem não é apagada e volta depois
   da visibilidade. Esse caso não tem teste.
