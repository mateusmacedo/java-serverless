# Guia: como o código sobe e por onde a requisição passa

Este guia é um roteiro de leitura do código da `sample-solution`, em Spring. Ele explica onde cada
Lambda começa, como os componentes são registrados e por onde uma requisição passa, com os arquivos
envolvidos em cada etapa. A [última parte](#como-as-lambdas-da-receipt-solution-sobem-quarkus) mostra
o mesmo para a `receipt-solution`, em Quarkus.

Para a referência de componentes e recursos, veja [Arquitetura](arquitetura.md). Para os diagramas
de execução em PlantUML, veja [Fluxos](fluxos.md).

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
| De onde vêm os clients da AWS | `platform/spring-aws/src/main/java/com/mmda/serverless/platform/spring/aws/AwsClientsAutoConfiguration.java`, ativada por `platform/spring-aws/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Nomes de bucket, tabela e demais recursos | `application.yml` de cada módulo, que lê as variáveis de ambiente definidas no `serverless.yml` |
| Lambdas, gatilho e máquina de estado da `receipt-solution` | `receipt-solution/serverless.yml`, blocos `functions:` e `stepFunctions:` |
| Onde a API da `receipt-solution` responde | `receipt-solution/api/src/main/java/com/mmda/serverless/receipt/api/ReceiptResource.java` |
| Os passos da `receipt-solution` | `receipt-solution/functions/src/main/java/com/mmda/serverless/receipt/functions/` (`IssueReceiptHandler` e `AnnounceReceiptHandler`) |
| Configuração dos módulos Quarkus | `application.properties` de `receipt-solution/api` e de `receipt-solution/functions` |

## Do build ao deploy

```text
mvn package
  ├─ api/target/api-lambda.zip              classes + lib/  (Spring MVC + SDKs s3 e sfn)
  └─ functions/target/functions-lambda.zip  classes + lib/  (Spring Cloud Function + 8 SDKs)

pnpm deploy:sample   (lê sample-solution/serverless.yml)
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

A `receipt-solution` segue o mesmo roteiro, com os zips que o `quarkus-parent` monta:

```text
mvn package
  ├─ receipt-solution/api/target/receipt-api-lambda.zip              (Quarkus REST)
  └─ receipt-solution/functions/target/receipt-functions-lambda.zip  (issue e announce)

pnpm deploy:receipt   (lê receipt-solution/serverless.yml; a sample precisa estar implantada)
  │
  ├─ functions:      3 Lambdas, todas com handler QuarkusStreamHandler::handleRequest
  │     api        ← receipt-api-lambda.zip
  │     issue      ┐ receipt-functions-lambda.zip
  │     announce   ┘   (a variável QUARKUS_LAMBDA_HANDLER diz qual handler cada uma executa)
  ├─ events:         API Gateway GET /receipts/{requestId} e POST /receipts → api
  ├─ stepFunctions:  máquina receipt-flow Issue → Announce, iniciada por uma regra no barramento da sample
  ├─ resources:      DynamoDB receipts e EventBridge do próprio barramento
  └─ environment:    TABLE_NAME, EVENT_BUS_NAME e STATE_MACHINE_ARN
```

O `pnpm deploy:local` empacota todos os módulos e implanta as stacks na ordem descrita em
[Implantação no Floci](../README.md#implantação-no-floci).

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
  └─ 3c. autoconfiguração: lê o AutoConfiguration.imports do jar spring-aws
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
  │               └─ PutEvents no EventBridge
  │                    ├─ regra da sample → fila SQS audit
  │                    └─ regra da receipt-solution → máquina receipt-flow (ver a última parte)
  └─ erro em qualquer passo (Catch)
        → SendToDeadLetterQueue → fila SQS dlq → RequestFailed (execução FAILED)

EventBridge Scheduler, a cada 5 minutos  ← serverless.yml: functions.reprocess.events
  └─ Lambda reprocess → ReprocessFunction.apply
        lê a dlq → attempt menor que 3: nova execução "requestId-2", "requestId-3"
                 → attempt igual a 3: descarta a mensagem
```

Os formatos de cada mensagem estão no [AsyncAPI](../contracts/sample-solution/asyncapi.yaml), e o contrato da rota no
[OpenAPI](../contracts/sample-solution/openapi.yaml).

## Como as Lambdas da receipt-solution sobem (Quarkus)

A `receipt-solution` segue a mesma ideia com outro framework: um zip só, várias Lambdas, e cada uma
escolhe o seu handler por variável de ambiente. Quem decide a ordem continua sendo o Step Functions,
agora iniciado pelo evento `RequestCompleted` da sample
([ADR 0011](decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)).

```text
Runtime java21 da Lambda
  │ 1. instancia o handler: QuarkusStreamHandler  (classe da extensão quarkus-amazon-lambda)
  ▼
Quarkus inicia a aplicação montada no build
  ├─ a injeção de dependências foi resolvida no build: IssueReceiptHandler (@Named("issue")),
  │   AnnounceReceiptHandler (@Named("announce")) e ClockProducer
  ├─ ReceiptConfig ← application.properties ← variáveis de ambiente
  └─ DynamoDbClient e EventBridgeClient ← extensões do Quarkus Amazon Services, client aws-crt
  │ 2. propriedade quarkus.lambda.handler, sobrescrita por QUARKUS_LAMBDA_HANDLER = issue
  │    → usa o handler com @Named("issue")
  ▼
IssueReceiptHandler.handleRequest(CompletedRequest, Context)
```

A cada invocação, o `QuarkusStreamHandler` converte o JSON de entrada para o tipo de entrada do
`RequestHandler` e serializa o retorno. No `issue`, a entrada é só o `detail` do evento, porque o
estado `Issue` da máquina usa `InputPath: $.detail`.

Arquivos, na ordem: `receipt-solution/serverless.yml`, `functions/src/main/resources/application.properties`,
`IssueReceiptHandler.java`, `AnnounceReceiptHandler.java`, `CompletedRequest.java` e `Receipt.java`.

A Lambda `api` da `receipt-solution` usa o mesmo `QuarkusStreamHandler`, mas da extensão
`quarkus-amazon-lambda-rest`: ele converte o evento do API Gateway numa requisição HTTP para o Quarkus
REST, e o `ReceiptResource` responde como num servidor comum. A emissão manual (`POST /receipts`)
inicia o `receipt-flow` com `{"detail": <corpo>}`, então daí em diante o caminho é o do evento.

O handler `io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest` é o que as próprias
extensões declaram no `target/sam.jvm.yaml` gerado pelo build. A escolha pelo `@Named` e a variável de
ambiente estão no guia oficial da extensão `quarkus-amazon-lambda`.

## Ordem sugerida de leitura

1. `sample-solution/serverless.yml`: os blocos `functions:` e `stepFunctions:` são o roteiro de tudo.
2. `StreamLambdaHandler.java` e depois `RequestController.java`: a entrada.
3. `ValidateFunction.java`, `PersistFunction.java` e `NotifyFunction.java`, na ordem da máquina de estado.
4. `ReprocessFunction.java`: o ciclo de falha.
5. `AwsClientsAutoConfiguration.java`: de onde vêm os clients que as classes recebem no construtor.
6. `receipt-solution/serverless.yml` e os dois handlers: como outra solução reage ao evento da sample.

## Onde mexer para...

| Quero... | O que muda |
|---|---|
| Adicionar um passo à máquina de estado | Nova classe `Function<In, Out>` com `@Component("nome")` em `functions`; nova Lambda no bloco `functions:` do `serverless.yml`, com `SPRING_CLOUD_FUNCTION_DEFINITION: nome`; novo estado no bloco `stepFunctions:`; permissões em `provider.iam`; testes ([Testes](testes.md)) |
| Adicionar uma rota HTTP | Método em `RequestController` ou novo `@RestController` em `api`; novo evento `http` na Lambda `api` do `serverless.yml`, porque o API Gateway só encaminha os caminhos declarados; atualizar o [OpenAPI](../contracts/sample-solution/openapi.yaml) |
| Usar outro serviço da AWS (Spring) | Entrada do SDK com as exclusões de cliente HTTP no `platform/spring-parent/pom.xml`; SDK opcional e nova configuração aninhada em `AwsClientsAutoConfiguration`; SDK declarado no pom do módulo que vai usar ([ADR 0005](decisoes/0005-clients-sincronos-com-crt.md)) |
| Usar outro serviço da AWS (Quarkus) | Extensão `quarkus-amazon-<serviço>` no pom do módulo, com versão do BOM do `quarkus-parent`; `quarkus.<serviço>.sync-client.type=aws-crt` no `application.properties` |
| Adicionar uma rota à API da `receipt-solution` | Método em `ReceiptResource` ou novo recurso JAX-RS em `receipt-solution/api`; novo evento `http` na Lambda `api` do `serverless.yml`; atualizar o [OpenAPI da receipt](../contracts/receipt-solution/openapi.yaml) |
| Adicionar um passo à `receipt-solution` | Nova classe `RequestHandler<In, Out>` com `@Singleton` e `@Named("nome")`; nova Lambda no `serverless.yml` com `QUARKUS_LAMBDA_HANDLER: nome`; novo estado no bloco `stepFunctions:`; permissões em `provider.iam` |
| Criar uma solução que reage a outra | Pasta nova com o parent do framework em `platform/` e `serverless.yml` próprio; evento `eventBridge` na máquina de estado, com `eventBusName` do barramento da produtora; primeiro passo idempotente ([ADR 0011](decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)) |
| Mudar o nome de um recurso | Bloco `custom.names` do `serverless.yml`; as variáveis de ambiente levam o novo nome às Lambdas |
| Mudar o limite de tamanho do payload | Recurso `MaxPayloadParameter` no `serverless.yml` (o valor fica no SSM e é lido a cada validação) |
| Mudar o número de tentativas | Variável `MAX_ATTEMPTS` nas Lambdas (padrão `3` em `functions/src/main/resources/application.yml`) |
