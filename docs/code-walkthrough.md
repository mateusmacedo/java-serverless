# Guia: como o código sobe e por onde a requisição passa

Este guia é um roteiro de leitura do código da `sample-solution`. Ele explica onde cada Lambda
começa, como os componentes são registrados e por onde uma requisição passa, com os arquivos
envolvidos em cada etapa.

Para a referência de componentes e recursos, veja [Arquitetura](architecture.md). Para os diagramas
de execução em Mermaid, veja [Fluxos](flows.md).

## Por que o fluxo não aparece navegando pelo código Java

Nenhuma classe Java chama outra. Uma classe nunca chama a seguinte: quem decide a ordem é o Step
Functions. As ligações ficam em três lugares fora do código Java:

1. **`sample-solution/serverless.yml`:** quais Lambdas existem, que classe cada uma executa, o que
   dispara cada uma e a ordem dos passos.
2. **Variáveis de ambiente**, declaradas no mesmo `serverless.yml`: qual função cada Lambda executa
   e o nome de cada recurso (bucket, tabela, filas).
3. **Autoconfiguração do Spring:** os clients da AWS aparecem prontos para injeção, sem `new` no
   código da solução.

Por isso a leitura começa pelo `serverless.yml`, não por uma classe Java.

## Mapa: onde começa cada coisa

| Pergunta | Onde olhar |
|---|---|
| Quais Lambdas existem, que classe cada uma executa e o que dispara cada uma | `sample-solution/serverless.yml`, bloco `functions:` |
| Ordem dos passos e o que acontece em caso de erro | `sample-solution/serverless.yml`, bloco `stepFunctions:` |
| Onde a Lambda HTTP começa | `sample-solution/api/src/main/java/com/mmda/serverless/sample/api/StreamLambdaHandler.java` |
| A rota HTTP | `sample-solution/api/src/main/java/com/mmda/serverless/sample/api/RequestController.java` |
| Onde as Lambdas dos passos começam | `FunctionInvoker`, classe da biblioteca Spring Cloud Function (fora do projeto), guiada pelas variáveis `MAIN_CLASS` e `SPRING_CLOUD_FUNCTION_DEFINITION` |
| Cada passo | `sample-solution/functions/src/main/java/com/mmda/serverless/sample/functions/` (`ValidateFunction`, `PersistFunction`, `NotifyFunction` e `ReprocessFunction`) |
| De onde vêm os clients da AWS | `shared/src/main/java/com/mmda/serverless/shared/aws/AwsClientsAutoConfiguration.java`, ativada por `shared/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Nomes de bucket, tabela e demais recursos | `application.yml` de cada módulo, que lê as variáveis de ambiente definidas no `serverless.yml` |

## Do build ao deploy

```text
mvn package
  ├─ api/target/api-lambda.zip              classes + lib/  (Spring MVC + SDKs s3 e sfn)
  └─ functions/target/functions-lambda.zip  classes + lib/  (Spring Cloud Function + 8 SDKs)

pnpm sls deploy --stage local   (lê sample-solution/serverless.yml)
  │
  ├─ functions:      5 Lambdas
  │     api        ← api-lambda.zip        handler: StreamLambdaHandler::handleRequest
  │     validate   ┐
  │     persist    ├ functions-lambda.zip  handler: FunctionInvoker::handleRequest
  │     notify     │   (o MESMO zip nas quatro; a variável SPRING_CLOUD_FUNCTION_DEFINITION
  │     reprocess  ┘    diz qual função cada uma executa)
  ├─ events:         API Gateway POST /requests → api
  │                  EventBridge Scheduler a cada 5 minutos → reprocess
  ├─ stepFunctions:  máquina de estado Validate → Persist → Notify, com Catch → DLQ
  ├─ resources:      S3, DynamoDB, SQS (dlq e audit), SNS, EventBridge, SSM e Secrets Manager
  └─ environment:    BUCKET_NAME, TABLE_NAME, STATE_MACHINE_ARN... injetadas em todas as Lambdas
```

## Como a Lambda `api` sobe e registra os componentes

Isso acontece uma vez por container, no cold start. As invocações seguintes reaproveitam o contexto.

```text
Runtime java21 da Lambda
  │ 1. instancia o handler declarado no serverless.yml: StreamLambdaHandler
  ▼
StreamLambdaHandler  (bloco static)
  │ 2. SpringBootLambdaContainerHandler.getAwsProxyHandler(ApiApplication.class)
  │    a biblioteca serverless-java-container substitui o Tomcat
  ▼
Spring Boot inicia ApiApplication  (@SpringBootApplication)
  ├─ 3a. varre o pacote ...sample.api       → registra RequestController
  ├─ 3b. @ConfigurationPropertiesScan        → registra ApiProperties
  │        application.yml: sample.bucket-name = ${BUCKET_NAME}  ← variável do serverless.yml
  └─ 3c. autoconfiguração: lê o AutoConfiguration.imports do jar shared
           → AwsClientsAutoConfiguration
              ├─ S3Client   criado  (o SDK s3 está no pom do api)
              ├─ SfnClient  criado  (o SDK sfn está no pom do api)
              └─ os outros 7 não são criados (@ConditionalOnClass: SDK ausente)
              região, credenciais e endpoint ← AWS_REGION, AWS_ENDPOINT_URL
  ▼
4. Contexto pronto, guardado no campo static HANDLER para as próximas invocações
```

Arquivos, na ordem: `StreamLambdaHandler.java`, `ApiApplication.java`, `ApiProperties.java`,
`api/src/main/resources/application.yml`, `AwsClientsAutoConfiguration.java`.

## Como as Lambdas dos passos sobem (exemplo: `validate`)

```text
Runtime java21 da Lambda
  │ 1. instancia o handler: FunctionInvoker  (classe da biblioteca, não do projeto)
  ▼
FunctionInvoker()  (construtor)
  │ 2. variável MAIN_CLASS = ...functions.FunctionsApplication → descobre qual aplicação subir
  ▼
Spring Boot inicia FunctionsApplication
  ├─ varre o pacote ...sample.functions e registra cada função pelo nome do @Component:
  │     "validate" → ValidateFunction     "persist"   → PersistFunction
  │     "notify"   → NotifyFunction       "reprocess" → ReprocessFunction
  │   (lazy-initialization no application.yml: registra as quatro, mas ainda não cria nenhuma)
  ├─ FunctionsProperties ← application.yml ← variáveis de ambiente
  └─ AwsClientsAutoConfiguration → clients dos 8 SDKs do pom, criados só quando pedidos
  │ 3. variável SPRING_CLOUD_FUNCTION_DEFINITION = validate
  │    → o catálogo de funções procura o bean "validate"
  ▼
ValidateFunction é criada neste momento
  └─ pede S3Client, SsmClient e SecretsManagerClient → só esses três clients nascem
```

A cada invocação, `FunctionInvoker.handleRequest` converte o JSON de entrada para o tipo de entrada
da função (`RequestContext`), chama `apply` e devolve o retorno serializado em JSON. Quem fornece
esse JSON é o Step Functions (ou o Scheduler, no caso do `reprocess`).

Arquivos, na ordem: `functions/src/main/resources/application.yml`, `FunctionsApplication.java`,
`FunctionsProperties.java`, a classe da função e `RequestContext.java`.

Esse comportamento do `FunctionInvoker` foi conferido no código-fonte do Spring Cloud Function
5.0.5: o construtor sobe o contexto, a aplicação vem de `MAIN_CLASS` e a função vem da propriedade
`spring.cloud.function.definition`.

## Uma requisição de ponta a ponta

```text
Cliente
  │ POST /requests  {"customer":"acme"}
  ▼
API Gateway                         ← serverless.yml: functions.api.events
  │ evento JSON
  ▼
Lambda api
  StreamLambdaHandler.handleRequest → proxyStream → DispatcherServlet
  → RequestController.create()
       ├─ s3.putObject         requests/<requestId>.json
       └─ sfn.startExecution   nome = requestId
                               entrada = {requestId, s3Key, attempt: 1}
  │ responde 202 {requestId, executionArn}   ← o cliente recebe aqui; o resto é assíncrono
  ▼
Step Functions "request-flow"       ← serverless.yml: stepFunctions
  │  a saída de cada passo vira a entrada do próximo (RequestContext em JSON)
  │
  ├─ Validate → Lambda validate → ValidateFunction.apply
  │               lê o limite (SSM), a chave (Secrets Manager) e o payload (S3)
  │               devolve RequestContext + signature + payloadBytes
  ├─ Persist  → Lambda persist  → PersistFunction.apply  → grava no DynamoDB
  ├─ Notify   → Lambda notify   → NotifyFunction.apply
  │               ├─ publica no SNS
  │               └─ PutEvents no EventBridge → regra → fila SQS audit
  └─ erro em qualquer passo (Catch)
        → SendToDeadLetterQueue → fila SQS dlq → RequestFailed (execução FAILED)

EventBridge Scheduler, a cada 5 minutos  ← serverless.yml: functions.reprocess.events
  └─ Lambda reprocess → ReprocessFunction.apply
        lê a dlq → attempt menor que 3: nova execução "requestId-2", "requestId-3"
                 → attempt igual a 3: descarta a mensagem
```

Os formatos de cada mensagem estão no [AsyncAPI](events/asyncapi.yaml), e o contrato da rota no
[OpenAPI](api/openapi.yaml).

## Ordem sugerida de leitura

1. `sample-solution/serverless.yml`: os blocos `functions:` e `stepFunctions:` são o roteiro de tudo.
2. `StreamLambdaHandler.java` e depois `RequestController.java`: a entrada.
3. `ValidateFunction.java`, `PersistFunction.java` e `NotifyFunction.java`, na ordem da máquina de estado.
4. `ReprocessFunction.java`: o ciclo de falha.
5. `AwsClientsAutoConfiguration.java`: de onde vêm os clients que as classes recebem no construtor.

## Onde mexer para...

| Quero... | O que muda |
|---|---|
| Adicionar um passo à máquina de estado | Nova classe `Function<In, Out>` com `@Component("nome")` em `functions`; nova Lambda no bloco `functions:` do `serverless.yml`, com `SPRING_CLOUD_FUNCTION_DEFINITION: nome`; novo estado no bloco `stepFunctions:`; permissões em `provider.iam`; testes ([Testes](testing.md)) |
| Adicionar uma rota HTTP | Método em `RequestController` ou novo `@RestController` em `api`; novo evento `http` na Lambda `api` do `serverless.yml`, porque o API Gateway só encaminha os caminhos declarados; atualizar o [OpenAPI](api/openapi.yaml) |
| Usar outro serviço da AWS | Entrada do SDK com as exclusões de cliente HTTP no `pom.xml` raiz; SDK opcional e nova configuração aninhada em `AwsClientsAutoConfiguration`; SDK declarado no pom do módulo que vai usar ([ADR 0005](decisions/0005-clients-sincronos-com-crt.md)) |
| Mudar o nome de um recurso | Bloco `custom.names` do `serverless.yml`; as variáveis de ambiente levam o novo nome às Lambdas |
| Mudar o limite de tamanho do payload | Recurso `MaxPayloadParameter` no `serverless.yml` (o valor fica no SSM e é lido a cada validação) |
| Mudar o número de tentativas | Variável `MAX_ATTEMPTS` nas Lambdas (padrão `3` em `functions/src/main/resources/application.yml`) |
