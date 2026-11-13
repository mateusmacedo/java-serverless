# Testes

Os testes seguem a pirâmide: muitos testes rápidos sem infraestrutura na base, alguns contra a AWS
emulada no meio e poucos contra a stack implantada no topo. Cada nível tem seu comando e sua
convenção de nome, para o build do dia a dia não depender de deploy.

| Nível                | Convenção | Comando         | Precisa de                                                  | Tempo aproximado                                                                                                                                            |
| -------------------- | --------- | --------------- | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Unitário e contrato  | `*Test`   | `mvn test`      | Nada além do JDK                                            | Segundos                                                                                                                                                    |
| Integração com Floci | `*IT`     | `mvn verify`    | Docker                                                      | Cerca de 30 s                                                                                                                                               |
| Ponta a ponta        | `*E2E`    | `pnpm test:e2e` | Docker, Floci do `compose.yaml` no ar e login no Serverless | O `QuoteFlowE2E` leva cerca de 16 min com a máquina ociosa ([rodada de fechamento do M5](#rodada-de-fechamento-do-m5)), além do deploy e dos outros módulos |

O `mvn verify` roda os dois primeiros níveis em todas as soluções. Os módulos `sample-solution/e2e`,
`receipt-solution/e2e` e `quote-solution/e2e` só entram no build com o perfil `-Pe2e`.

Para verificar uma solução só, liste os módulos dela. No Maven 3.9, `mvn -pl <solução> -am verify`
seleciona só o POM agregador da solução e não roda teste nenhum:

```bash
mvn -B verify -pl quote-solution/domain,quote-solution/api,quote-solution/functions -am
mvn -B verify -pl provider-solution/domain,provider-solution/api,provider-solution/functions,provider-solution/simulators -am
```

Outra forma é acrescentar `-amd`, que inclui os módulos filhos: `mvn -B verify -pl quote-solution -am -amd`.

Antes de um `verify` que valida um marco, recompile tudo, tocando os fontes ou com `mvn clean verify`. A
IDE grava classes em `target/`, e uma classe que ela compilou com erro ("Unresolved compilation
problems") parece em dia para o Maven: o javac não a recompila, e o surefire a pula sem aviso. No
primeiro `verify` da integração do M4, isso tirou o `QuoteEventsContractTest` da contagem (79 testes
unitários no `quote-functions` em vez de 85), sem nenhuma falha. No M5, a extensão Java do VS Code
recompilou em `target/` cada fonte tocado antes do Maven, e um `touch` em massa com a IDE aberta deixou
14 classes de teste quebradas no `quote-functions`: o failsafe rodou 0 testes, sem falha. Feche a IDE ou
desligue o `java.autobuild` antes do `verify` de um marco e confira, depois dele, que toda classe de teste
tem relatório novo e que nenhum `.class` tem "Unresolved compilation".

```bash
find platform/spring-aws provider-solution quote-solution -path '*/src/*' -name '*.java' -exec touch {} +
```

## Unitários

Ferramentas: JUnit 6, Mockito e AssertJ. No Spring, nas versões do `spring-boot-starter-test`; no
Quarkus, nas do BOM do Quarkus, com o AssertJ fixado no `quarkus-parent` na mesma versão do Spring.

| Módulo                | Classe                                                                                                                          | O que cobre                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `spring-aws`          | `AwsClientsAutoConfigurationTest`                                                                                               | Um client por SDK presente, client omitido quando o SDK falta, client da aplicação preservado, URL do S3 em virtual-host por padrão e em path-style quando configurado                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `functions`           | `ValidateFunctionTest`                                                                                                          | Assinatura HMAC conferida com o vetor de teste público do HMAC-SHA256, limite exato, payload acima do limite e payload vazio                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `functions`           | `PersistFunctionTest`                                                                                                           | Tabela e atributos gravados no DynamoDB                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `functions`           | `NotifyFunctionTest`                                                                                                            | Mensagem no SNS, evento compatível com a regra do EventBridge e erro quando o EventBridge rejeita o evento                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `functions`           | `ReprocessFunctionTest`                                                                                                         | Reinício com `requestId-2`, descarte no limite, lote completo, fila e tamanho de lote configurados                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `functions`           | `ReprocessCommandTest`                                                                                                          | Ajuste do lote para a faixa de 1 a 10                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `api`                 | `RequestControllerTest`                                                                                                         | `202` com o upload e a execução conferidos; `415` e `400` sem chamar a AWS                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `receipt-functions`   | `IssueReceiptHandlerTest`                                                                                                       | Gravação condicional por `requestId` e devolução do comprovante já emitido quando o evento se repete                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `receipt-functions`   | `AnnounceReceiptHandlerTest`                                                                                                    | `ReceiptIssued` com o comprovante no `detail` e erro quando o EventBridge rejeita o evento                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `receipt-api`         | `ReceiptResourceTest`                                                                                                           | `@QuarkusTest` com os clients trocados por `@InjectMock`: consulta `200` e `404`, emissão `202` com `{"detail": ...}` na entrada da execução, `400` e `415` sem iniciar execução                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `provider-domain`     | `ReusePlanTest`                                                                                                                 | Decisão do pedido de enriquecimento diante do `REUSE#` e do `INFLIGHT#`: `HIT` só com evidência válida, reutilizável e da política de reuso em vigor (`NOT_FOUND` declarado sim, `FAILED` nunca); `JOIN` na lease válida, inclusive no próprio instante `leaseUntil`; liderança na versão seguinte, continuando a cadeia na lease vencida e começando outra depois de `COMPLETED`; nunca seguidor de si mesmo; conclusão que responde só aos seguidores da própria cadeia                                                                                                                                                                                                                                                                            |
| `provider-domain`     | `CallbackSignatureTest`                                                                                                         | Assinatura `sha256=` com o hexadecimal do HMAC dos bytes exatos do corpo; qualquer mudança no corpo, no segredo ou no cabeçalho é rejeitada; envelope do callback (`externalEventId`, correlação e `result`); as duas metades do encontro no `CORR#`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `provider-domain`     | `InsurerProtocolTest` (casos do M3)                                                                                             | Resultado do callback classificado como a resposta síncrona; `202` com um `providerRequestId` que nenhum callback poderia levar é `UNAVAILABLE` sem retry; veredito terminal convertido em resultado, com a resposta bruta só na oferta; `InsurerResultRecorded` montado da consulta gravada igual ao montado do pedido                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `provider-functions`  | `ServerlessConfigurationTest` (casos do M3)                                                                                     | `sim-callbacks` com DLQ e visibilidade de 6 vezes o timeout do sender; só o callback do `vehicle-beta-late` passa da espera da seguradora na quote, nos stages `local` e `dev` (o caso `dev` era ignorado até o M6, quando a quote passou a declarar a espera da AWS); rota de callbacks na `api`, com papel próprio que lê o `callback-secret`; os papéis da `api` e do `simCallbackSender` com exatamente as ações que o código e o consumo da fila usam, o do sender sem `sqs:ChangeMessageVisibility` (`callbackRolesGrantOnlyWhatTheirCodeAndTheQueueConsumerUse`); quota de cada provedor como parâmetro do stage                                                                                                                              |
| `provider-simulators` | `BetaInsurerSimulatorControllerTest`                                                                                            | `202` com `providerRequestId` e callback agendado com o `clientReference`; recusa e pendência por callback; cenários silencioso, duplicado (mesmo evento duas vezes), atrasado e antecipado (sem atraso, só com o `providerRequestId`); situação `PROCESSING` até o resultado ficar pronto e 404 para referência desconhecida; pedido sem `clientReference` rejeitado                                                                                                                                                                                                                                                                                                                                                                                |
| `provider-simulators` | `SimCallbackSenderTest`                                                                                                         | Callback assinado aceito pela validação da API de callbacks; callback antecipado sem `clientReference`; resposta recusada falha só a própria mensagem                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `quote-domain`        | `RulesTest` (casos do M3)                                                                                                       | `sel-1` com o ano do perfil enriquecido, conferido antes do uso; o T26 com veículo enriquecido anterior a 2000 e ano de entrada recente; o ano do snapshot como reserva quando o perfil não o traz                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `quote-domain`        | `ConsolidationTest` (casos do M3)                                                                                               | Precedência do PRD §8 nos 15 pares de resultados terminais de duas seguradoras, nas duas ordens; o T07 (o motivo da recusa fica no resultado, e o `outcome` não tem motivo); recusa com pendência é `NEEDS_INFORMATION`, não `NO_OFFER`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `quote-functions`     | `FunctionTimingConfigurationTest` (casos do M3; os de orçamento rodam nos stages `local` e `dev` desde o M6)                    | O orçamento cobre todas as seguradoras do laboratório, então nenhuma selecionada é cortada pelo limite; o `Map` despacha cada seguradora selecionada e registra a própria falha; timeouts estáticos, porque o Floci ignora `TimeoutSecondsPath`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `quote-functions`     | `ProviderEventsConsumptionTest` e `QuoteEventsContractTest` (casos do M3)                                                       | Os exemplos de `InsurerResultRecorded` de cada seguradora no AsyncAPI da provider viram `RESULT` e oferta; o `InsurerQuoteRequested` segue o contrato para cada seguradora                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `quote-functions`     | `EnrichmentEvidenceRecordingTest`                                                                                               | Evidência usada gravada com `evidenceId`, `queryId` e `policyVersion` em `HIT`, `JOIN` e `MISS`; `NOT_FOUND` compartilhado com as referências declaradas; evento fora do contrato devolvido à fila sem evidência, antes e depois do prazo do waiter, sem virar evidência tardia; resultado tardio com a referência da evidência. Os 17 eventos fora do contrato cobrem `reuseDecision` ausente ou desconhecido, `evidenceId`, `queryId` ou `policyVersion` ausentes, `policyVersion` vazio, fora do pattern ou que não é texto, `queryId` e `evidenceId` fora do pattern, `HIT` de resultado `FAILED`, `FOUND` sem `result` e `NOT_FOUND` com `notFoundRefs` ausente, vazio, que não é lista, com referência que o pedido não tem ou que não é texto |
| `provider-domain`     | `OperationsTest` (M4)                                                                                                           | Ocorrência aberta observada de novo só muda quando o problema se repete; `issueId` determinístico pelo tipo e pelo alvo; cada revisão é um evento próprio, e só a primeira é `OperationIssueRaised`; só `UNKNOWN_QUERY` com `queryId` é `reconcilable`; `QueryReconciled` só com o resultado, sob `eventId` determinístico; mensagem de DLQ reenviada só abaixo do limite e antes do prazo do pedido                                                                                                                                                                                                                                                                                                                                                 |
| `provider-domain`     | `InsurerStatusTest` (M4)                                                                                                        | `COMPLETED` da API de situação classificado como o callback; `COMPLETED` sem resultado legível é `UNAVAILABLE`; `PROCESSING`, referência desconhecida e falhas nunca concluem a consulta                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `provider-functions`  | `ServerlessConfigurationTest` (casos do M4)                                                                                     | Fila `reconcile-requests` com o orçamento das filas de pedidos, DLQ e a regra do `QueryReconcileRequested`; toda fila de trabalho com DLQ que o `reconciler` devolve à origem; idades do `reconciler` acima do timeout da `api`, do ciclo de 1 min e da lápide da liderança; alarmes de idade e volume de cada DLQ e da `stream-failures`; alarmes das métricas próprias no namespace que as funções gravam, com os limiares do stage                                                                                                                                                                                                                                                                                                                |
| `provider-functions`  | `ServerlessConfigurationTest` (casos do M6)                                                                                     | Todo `${param:...}` referenciado sem valor padrão tem valor nos stages `local` e `dev`; `AWS_ENDPOINT_URL` e `AWS_ENDPOINT_URL_S3` condicionais a `HasAwsEndpointUrl`, com o endereço do Floci e o path-style só no `local` e o `providersBaseUrl` da AWS no `dev`; os 16 alarmes notificam o `AlarmsTopic`, e a assinatura por e-mail depende de `HasAlarmEmail`; o papel das funções de trabalho sem `sqs:GetQueueAttributes` nas DLQs                                                                                                                                                                                                                                                                                                             |
| `provider-functions`  | `ProducedEventSchemaTest` e `EventContractTest` (casos do M4)                                                                   | `OperationIssueRaised` e `OperationIssueUpdated` de todo tipo e status e `QueryReconciled` de todo resultado seguem o schema; os três eventos novos serializam exatamente como os exemplos do AsyncAPI; o exemplo do `QueryReconcileRequested` no AsyncAPI da quote é lido pelo consumidor da provider                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `quote-domain`        | `ReconciliationTest` (M4)                                                                                                       | Decisões do `reconciler` sem AWS: finalização da execução encerrada sem consolidar, mesmo antes do prazo (T20); `STUCK_VERSION` só depois do prazo com a margem; situação desconhecida ou em andamento nunca finalizada; waiter expirado sem resultado gravado é o timeout normal e não abre ocorrência; resultado gravado só abre `TASK_EXPIRED` quando a execução encerrada não o recebeu; mensagem da `results-dlq` reenviada duas vezes e descartada na terceira chegada (T31), e a que não identifica o resultado, descartada na hora; `issueId` estável por tipo e alvo e separado do da provider                                                                                                                                              |
| `quote-functions`     | `FunctionTimingConfigurationTest` (casos do M4; os de prazo, alarmes e limiares rodam nos stages `local` e `dev` desde o M6)    | Prazo do pedido de seguradora limitado ao que resta da cotação; alarmes de volume e idade da `results-dlq` e da `stream-failures`; alarmes de outbox e de tarefas expiradas sobre as métricas que o `reconciler` publica; limiares e parâmetros do `reconciler` do desenho; papel do `reconciler` com os deveres do M4, sem `sqs:GetQueueAttributes` na `results-dlq` desde o M6, e `UpdateItem` no papel da API, para a nova versão                                                                                                                                                                                                                                                                                                                 |
| `quote-functions`     | `ReconcilerMetricsTest` (M4)                                                                                                    | A linha EMF declara `ExpiredTasks` e `OutboxPendingAgeSeconds` no namespace configurado, com a dimensão `Stage`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `quote-functions`     | `ExpiredTaskDeliveryTest` (M4)                                                                                                  | Saídas de `TaskSucceeded` no formato do histórico do Step Functions: o resultado levado pelo `SendTaskSuccess` e o `Payload` do passo de timeout que leu o resultado gravado contam como entrega, na seguradora e no enriquecimento; o passo de timeout sem o resultado, a saída de outra seguradora e a de outra tarefa, como a consolidação, não contam                                                                                                                                                                                                                                                                                                                                                                                            |
| `provider-functions`  | `StatusReadsTest` (M4)                                                                                                          | As leituras da API de situação param no teto do ciclo (`statusReadBudgetSeconds`) e, para a seguradora que não respondeu, até o ciclo seguinte, sem parar as das outras; o `reconcileWorker` não tem teto                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `quote-domain`        | `SelectionsTest` (M5)                                                                                                           | Decisão da seleção sem AWS: oferta válida da versão atual é gravada; T28 (a mesma oferta devolve a seleção existente, mesmo depois de expirar, e outra oferta é conflito); T18 (oferta expirada recusada também no instante exato do `validUntil`); versão substituída vem antes da seleção existente e da expiração; versão que ainda não existe e oferta fora da versão são 404; `selectionId` único por versão e opaco                                                                                                                                                                                                                                                                                                                            |
| `quote-domain`        | `OperationsTest` (M5)                                                                                                           | `RECONCILE` só na ocorrência aberta, com consulta, `reconcilable` e para quem tem `operations:reconcile`; tipos de ocorrência das duas soluções, com só os da provider projetados; só a `STUCK_VERSION` se resolve sozinha; `operationId` opaco e único; as fases só avançam (`Phase.atOrBefore`)                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `provider-simulators` | `LabCatalogTest` (M5)                                                                                                           | O catálogo empacotado é o próprio `contracts/fixtures/catalog.json`; todo veículo de cenário dos simuladores está nele, inclusive os das regras que a quote aplica; anos de habilitação completos desde o `licenseDate`; perfis com as coberturas e o `COMPREHENSIVE` presente; id de veículo repetido entre organizações, condutor ou perfil repetido e catálogo sem o `COMPREHENSIVE` são recusados na subida                                                                                                                                                                                                                                                                                                                                      |
| `provider-simulators` | `EnrichmentSimulatorControllerTest`, `AlphaInsurerSimulatorControllerTest` e `BetaInsurerSimulatorControllerTest` (casos do M5) | Veículo e condutor do catálogo perfilados por ele; anos de habilitação pela data civil de `America/Sao_Paulo` (relógio fixo em 2026-03-15T01:30Z, 22h30 de 14/03 em São Paulo: a Ana, habilitada em 2012-03-15, tem 13 anos); `vehicle-classic-1998` do catálogo mantém o ano anterior a 2000; `vehicle-not-found` do catálogo continua 404 declarado; condutor sem `licenseDate` tem zero anos; ofertas com as coberturas do perfil pedido e franquia sobre a `COLLISION` do perfil padrão; perfil fora do catálogo recusado, na beta sem agendar nada                                                                                                                                                                                              |
| `quote-functions`     | `FunctionTimingConfigurationTest` (casos do M5; nos stages `local` e `dev` desde o M6)                                          | A fila `issues` recebe os três eventos operacionais da provider pela regra própria, com DLQ, visibilidade e alarmes de idade e volume da `issues-dlq`; o `reconciler` reprocessa a DLQ, sem `sqs:GetQueueAttributes` nela desde o M6; o `IssueProjectorRole` tem exatamente as ações do código e do consumo da fila; dois consumidores SQS (`resume` e `issue-projector`)                                                                                                                                                                                                                                                                                                                                                                            |
| `quote-functions`     | `FunctionTimingConfigurationTest` (casos do M6)                                                                                 | Todo `${param:...}` referenciado sem valor padrão tem valor nos stages `local` e `dev`, inclusive `awsEndpointUrl`, `jwtIssuerBase`, `alarmEmail` e `insurerTimeoutSeconds`; o `local` só sobrescreve `awsEndpointUrl`, `jwtIssuerBase` e `insurerTimeoutSeconds`, e o `dev` não tem endereço do Floci; `AWS_ENDPOINT_URL` condicional a `HasAwsEndpointUrl` e `JWT_ISSUER` montado do `jwtIssuerBase`; os 8 alarmes notificam o `AlarmsTopic`, e a assinatura por e-mail depende de `HasAlarmEmail`; o Scheduler assume só o `ReconcilerRole`, com `aws:SourceAccount`, e só esse papel invoca a própria função; cada papel escreve log só no grupo da própria função, sem `ManagedPolicyArns`                                                      |
| `quote-functions`     | `ProviderEventsConsumptionTest` (caso do M5)                                                                                    | Os exemplos operacionais do AsyncAPI da provider (`QueryReconciled`, `OperationIssueRaised` e `OperationIssueUpdated`) passam pelo `issue-projector` com tabela simulada e viram `ISSUE#` ou `OPERATION#` com o `CONSUMED#`; só a ocorrência grava `QUERYREF#`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `quote-functions`     | `QuoteEventsContractTest` (casos do M5)                                                                                         | O `OfferSelected` e o `QueryReconcileRequested` que a API grava seguem o AsyncAPI da quote; toda operação `receive` aponta uma mensagem que existe no AsyncAPI da provider, e o conjunto consumido é exatamente `EnrichmentResolved` e `InsurerResultRecorded` na fila `results` e `QueryReconciled`, `OperationIssueRaised` e `OperationIssueUpdated` na fila `issues`                                                                                                                                                                                                                                                                                                                                                                              |

Os testes das functions da `sample-solution` simulam os clients do SDK com
`Answers.CALLS_REAL_METHODS`: o código chama a sobrecarga com `Consumer`, que monta o request e chama
a sobrecarga com o request pronto, que é a simulada. Por isso essas classes usam o modo leniente do
Mockito; as verificações de cada teste continuam explícitas. Os handlers da `receipt-solution` chamam
direto a sobrecarga com o request pronto, então os testes deles usam o modo estrito.

## Contrato

`RequestControllerContractTest` confere requisições e respostas do controller contra o
[OpenAPI](../contracts/sample-solution/openapi.yaml), com o `openapi-request-validator-mockmvc`. Nos casos `400` e `415` a
requisição é inválida de propósito, então só a resposta é conferida.

O teste pega divergência de verdade: com a resposta `415` removida do OpenAPI, ele falha com
`validation.response.status.unknown`. Ao mudar a API, atualize o contrato no mesmo commit.

`ReceiptApiContractTest` faz o mesmo para a API da `receipt-solution`, contra o
[OpenAPI da receipt](../contracts/receipt-solution/openapi.yaml), com o `openapi-request-validator-restassured`
como filtro do RestAssured. Ele também pega divergência: com um campo obrigatório a mais no
`Receipt` do contrato, a consulta falha com `validation.response.body.schema.required`.

`CallbackApiContractTest`, no `provider-api`, confere o controller de callbacks contra o
[OpenAPI do callback](../contracts/provider-solution/openapi.yaml), com o `openapi-request-validator-mockmvc` num
`@WebMvcTest`. Cobre o callback processado (`200`), o retido na inbox (`202`), a assinatura errada
(`401`, sem chegar à inbox), o envelope inválido e o provedor desconhecido (`400` e `404`), o `result`
fora do schema que ainda chega à inbox, o corpo grande demais (`413`, sem chegar à inbox) e o pedido sem
assinatura, que não escreve o caminho no log. Na quote, o `QuoteApiContractTest` ganhou no M3 a versão
`PARTIAL` com uma seguradora em `TIMED_OUT` (T08) e, no M4, o `POST /v1/quotes/{id}/versions` (T17):
nova versão conforme o contrato, que substitui a atual numa só transação; `expectedVersion` que já não é
a atual é `409 VERSION_CONFLICT`; nova versão depois de uma seleção é `409 QUOTE_ALREADY_SELECTED`;
reenvio com a mesma chave e o mesmo body é replay; body inválido é `400 VALIDATION_ERROR`; sem a
permissão de escrita, `403`; cotação de outra organização, `404`. No M4, o `QuoteEventsContractTest` passou a conferir também o exemplo
`unknown-query` do `QueryReconcileRequested`, com 5 exemplos contra o schema.

No M5, o `contracts/openapi.yaml` 1.0.0 é o contrato compartilhado com o frontend, e o `quote-api` o
confere em quatro classes. As três primeiras chamam a API com o filtro do RestAssured e uma tabela
simulada no lugar do DynamoDB; o `ContractFixturesTest` passa as fixtures direto pelo validador:

| Classe                               | O que cobre                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `QuoteApiContractTest` (casos do M5) | Toda rota implementada está no contrato e toda rota do contrato está implementada (T22); toda rota responde 403 sem a permissão que o `x-permissions` declara (T30); token de outra chave ou com payload adulterado é 401; o catálogo responde exatamente as páginas de `catalog-pages.json` (T22), só da organização, com busca e paginação; a listagem lê o índice da organização com filtros e cursor; filtros inválidos são 400; cursor fora do período ou acima do limite da chave é 400; `quoteId` maior que uma chave é 404 sem chegar à tabela; o `search` conta caracteres como o contrato (60 emojis passam, 101 não)                                                                                                                                                       |
| `SelectionContractTest`              | Seleção durante `QUOTING` grava a intenção e o evento numa transação; T28 (a mesma oferta devolve a seleção existente, mesmo com outra chave; outra oferta é `SELECTION_ALREADY_EXISTS`); T18 (oferta expirada recusada sem gravar nada; oferta fora da versão e cotação de outra organização são 404); versão substituída é conflito; T29 (a nova versão que vence a corrida torna a seleção `VERSION_SUPERSEDED`, a seleção concorrente que vence decide a resposta da outra, e depois da seleção um `NEEDS_INFORMATION` posterior não oferece ação); oferta que expira entre a leitura e a gravação é `OFFER_EXPIRED`; reenvio com a mesma chave é replay e outro body é conflito; falha antes da seleção durável é 503; sem `offers:select`, 403 (T30); pedidos inválidos são 400 |
| `OperationsContractTest`             | Ocorrências conforme o contrato, com `RECONCILE` só onde é permitido; filtros no índice da organização; reconciliação de consulta conhecida grava auditoria e pedido numa transação; consulta desconhecida ou de outra organização é 404; sem `operations:reconcile`, 403 (T30); reenvio com a mesma chave devolve a mesma operação; `reason` ausente é 400 e conta caracteres como o contrato (266 code points, 516 unidades UTF-16, passam; 501 emojis não); ids e cursor maiores que uma chave são 404 ou 400, sem erro interno; a operação segue o contrato e é da organização                                                                                                                                                                                                    |
| `ContractFixturesTest`               | T22: as trocas dos 14 arquivos de `contracts/fixtures` (hoje 61, com o mínimo de 50 afirmado no teste) seguem o contrato, a requisição também, menos nas trocas marcadas `invalidRequest`, que são inválidas de propósito; as fixtures cobrem as fases, os `outcome`, os status de seguradora, os motivos de indisponibilidade, os seis códigos de 409 e os erros 400, 401, 403, 404, 409, 429 e 503; os enums do contrato são os do Java                                                                                                                                                                                                                                                                                                                                             |

A tabela simulada dos testes de contrato (`DynamoDbLimits`) reproduz três recusas do DynamoDB que o
mock e o Floci não fazem: chave de partição acima de 2048 bytes, chave de ordenação acima de 1024 e
`ExclusiveStartKey` fora da condição da chave, todas com `ValidationException`. Sem ela, os testes de
cursor e de id longo passariam mesmo com a API respondendo 500 na AWS.

No `quote-functions`, o `QuoteEventsContractTest` confere o `OfferSelected` e o `QueryReconcileRequested`
contra o AsyncAPI da quote (0.3.0) e o consumo declarado nele, e o `ProviderEventsConsumptionTest` passa
pelo `issue-projector` os exemplos operacionais do AsyncAPI da provider (tabela dos unitários).

Entre as soluções, `CompletedRequestContractTest`, na `receipt-solution`, lê o exemplo do
`RequestCompleted` documentado no [AsyncAPI da sample](../contracts/sample-solution/asyncapi.yaml), copiado em
`src/test/resources/request-completed-event.json`, e confere que o consumidor extrai `requestId`,
`signature` e `payloadBytes` do `detail`. Se a sample mudar o evento, atualize o exemplo e o fixture
juntos.

## Integração com Floci

Cada módulo sobe um único Floci (`floci/floci:2.4.0`) pelo módulo oficial
[`testcontainers-floci`](https://github.com/floci-io/testcontainers-floci), artefato
`testcontainers-floci-aws` 2.17.0, sobre o Testcontainers 2.0.5 que o Spring Boot 4.0 e o Quarkus
3.40 usam. A classe base `FlociIntegrationTest` passa endpoint, região e
credenciais para as propriedades de sistema do AWS SDK (`aws.endpointUrl`, `aws.region`,
`aws.accessKeyId`, `aws.secretAccessKey`). Assim os testes usam a autoconfiguração real do `spring-aws`.

| Classe                     | O que cobre                                                                                                                                                 |
| -------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RequestFlowFunctionsIT`   | As quatro funções com S3, SSM, Secrets Manager, DynamoDB, SNS com assinante SQS, EventBridge com regra para a fila de auditoria, SQS e Step Functions reais |
| `RequestControllerFlociIT` | A aplicação HTTP completa gravando no S3 e iniciando uma execução real                                                                                      |

Cada teste cria os recursos de que precisa antes do contexto Spring, com os mesmos papéis do
`serverless.yml`. A imagem do Floci vem da propriedade `floci.image` do POM raiz.

Na `receipt-solution`, o `ReceiptFunctionsIT` é um `@QuarkusTest` com o recurso de teste
`FlociResource`, que sobe o Floci, define as mesmas propriedades de sistema e cria tabela, barramento
e uma fila de captura antes de o Quarkus iniciar. Os clients do Quarkus Amazon Services chegam ao
Floci pela cadeia padrão do SDK, sem `endpoint-override`, como na Lambda. O `receipt-api` tem o
próprio `FlociResource`, que cria a tabela e uma máquina de estado de um passo para o
`ReceiptApiFlociIT`.

| Classe               | O que cobre                                                                                                                                                                 |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ReceiptFunctionsIT` | `issue` gravando no DynamoDB e devolvendo o mesmo comprovante na repetição; `announce` publicando `ReceiptIssued`, capturado por regra e fila SQS                           |
| `ReceiptApiFlociIT`  | Consulta de um comprovante gravado na tabela real, `404` para pedido sem comprovante e emissão iniciando uma execução real, com o `detail` conferido no `DescribeExecution` |

Na plataforma de cotação, o M2 acrescenta o `EnrichmentReuseIT`, no `provider-functions`. Cada caso usa
um veículo próprio, então começa sem `REUSE#` nem `INFLIGHT#` da sua `queryKey`, e o `TestClock`, comum
à provider e ao provedor simulado do teste, vence a validade da evidência e a lease sem espera real. No
`quote-functions`, o `QuoteFunctionsFlociIT` ganhou o lado da quote de T04 (com `HIT` e `JOIN`), T25 e
T27.

| Classe                                | O que cobre                                                                                                                                                                                                                               |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `EnrichmentReuseIT`                   | 17 casos, listados abaixo                                                                                                                                                                                                                 |
| `QuoteFunctionsFlociIT` (casos do M2) | Lado da quote de T04, com `HIT` e `JOIN` seguindo o mesmo fluxo do `MISS` e guardando a referência da evidência; T25 (`NOT_FOUND` termina `NEEDS_INFORMATION` sem seguradora) e T27 (`FAILED` termina `TECHNICAL_FAILURE` sem seguradora) |

Casos do `EnrichmentReuseIT` (as regras estão no §5 e no §8 do [desenho técnico](plataforma-de-cotacao.md)):

| Caso                                                                                 | O que cobre                                                                                                                                                    |
| ------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `t04_reuseHitCompletesWithoutExternalCall`                                           | `HIT` sem chamada externa                                                                                                                                      |
| `t05_expiredReuseStartsNewQueryAndKeepsHistory`                                      | Evidência vencida abre outra consulta e mantém o histórico                                                                                                     |
| `t06_hundredEquivalentConcurrentRequestsElectOneLeader`                              | 1 líder, 1 chamada, 99 seguidores e 100 `EnrichmentResolved` com o mesmo `evidenceId`                                                                          |
| `t15_staleLeaderCannotOverwriteAfterLeaseTransfer`                                   | Líder antigo, depois da transferência da lease, conclui só a própria consulta                                                                                  |
| `declaredNotFoundIsReusedWithTheDeclaredReferences`                                  | `NOT_FOUND` declarado é reutilizado com as referências declaradas                                                                                              |
| `failureIsNeverReusedAndTheNextRequestLeadsANewChain`                                | `FAILED` nunca entra no índice, e o pedido seguinte começa outra cadeia                                                                                        |
| `redeliveriesOfTheLeaderAndOfAFollowerNeverDuplicateTheResolution`                   | Reentregas do líder e do seguidor não duplicam a resposta                                                                                                      |
| `registeredFollowerThatFindsTheLeadershipCompletedPublishesTheRecordedResult`        | Seguidor registrado que encontra a liderança concluída publica o resultado gravado                                                                             |
| `interruptedLeaderConcludesItsLeadershipWithFailureForItsFollowers`                  | Líder interrompido conclui a liderança com `FAILED` e responde aos seguidores                                                                                  |
| `observedAtAheadOfTheReceptionDoesNotExtendTheValidity`                              | `observedAt` adiantado fica na evidência como declarado, mas a validade conta do `receivedAt`                                                                  |
| `requestIsDeduplicatedByItsWorkRefNotByTheEventIdItArrivedWith`                      | Reentrega com outro `eventId` é descartada pelo `CONSUMED` do `workRef`, sem consulta nova                                                                     |
| `leaderRedeliveryAnswersAFollowerThatTheFanOutNeverReached`                          | Reentrega do líder refaz o fan-out e responde ao seguidor que ficou sem resposta                                                                               |
| `interruptedLeaderOfAnOlderChainLeavesTheCurrentLeadershipWithTheSameVersionAlone`   | Líder interrompido de uma cadeia antiga conclui a própria consulta (`UNKNOWN`) sem tocar a liderança atual, que tem a mesma `fencingVersion` e outro `queryId` |
| `leaderWhoseRequestWasAnsweredMeanwhileStillConcludesForTheIndexAndItsFollowers`     | Líder cujo pedido foi respondido por outra entrega conclui a consulta, a liderança e o índice, sem o próprio evento, e faz o fan-out                           |
| `followerWhoseRegistrationLosesToTheConclusionPublishesTheRecordedResult`            | Seguidor cujo registro perde para a conclusão publica para si o resultado gravado (`JOIN`)                                                                     |
| `requestThatLosesTheIndexToAnotherLeaderRereadsItAndUsesTheNewEvidence`              | Pedido cuja liderança é cancelada pelo `REUSE#` trocado relê o índice e usa a evidência nova (`HIT`)                                                           |
| `leaderThatLosesTheIndexAtTheConclusionRereadsTheRevisionAndStillIndexesItsEvidence` | Líder que encontra o `REUSE#` trocado na conclusão relê a revisão e grava a própria evidência no índice                                                        |

O M3 acrescenta o módulo `provider-api`, com o próprio `FlociIntegrationTest`, e casos de callback,
quota e `Map` nas duas soluções. As regras estão no §8 e no §9 do [desenho técnico](plataforma-de-cotacao.md).
O `provider-functions` tem dependência de teste do `provider-api`, só das classes (exclusão `*:*` das
transitivas, sem a pilha web), e a ponte `CallbackApi`, em
`functions/src/test/java/com/mmda/serverless/provider/api`, usa o `CallbackInbox` real. Assim o
`InsurerCallbackIT` cobre o encontro no `CORR#` com o código real das duas metades, nas duas ordens,
inclusive o callback com `clientReference` que conclui a consulta antes de o `202` ser gravado
(`recordAccepted` devolve `false`), disparado durante a chamada pelo `ProviderStub.beforeAnswering`. A
ordem "callback retido na inbox antes do `202`" só é coberta aqui: no `quote-e2e`, o callback antecipado
chega depois do `202` (§13 do desenho).

| Classe                                  | O que cobre                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CallbackApiFlociIT`                    | Callback assinado com `clientReference` grava o resultado com o pedido consumido; assinatura errada ou ausente não grava nada; T11 (repetição reconhecida pela inbox, um só resultado); T12 nas duas ordens (callback depois da metade do worker, associado pelo `CORR#`, e callback retido antes dela, associado de novo quando repetido); callback mais rápido que o `202`, encontrado pelo `clientReference`; consulta já concluída, em que só a inbox passa a `PROCESSED`; `result` ilegível ou com campo de tipo errado, guardado e convertido em `UNAVAILABLE`; callback fora do contrato ou de outro provedor recusado                                                                                                                                                 |
| `StreamLambdaHandlerFlociIT`            | A Lambda da `api` pelo handler real, com o evento de proxy do API Gateway: a assinatura confere com os bytes que o `serverless-java-container` entrega ao controller, e a errada é recusada                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `InsurerCallbackIT`                     | `202` mantém a consulta em voo até o callback; T12 nas duas ordens, com os callbacks pelo `CallbackApi`: retido antes do `202` e concluído pela metade do worker, ou chegado depois da metade do worker e concluído pela API; callback com `clientReference` durante a chamada, que conclui a consulta antes de o `202` ser gravado: o `202` não muda a consulta, e a metade do worker no `CORR#` não acha pendência nem grava outro resultado; o mesmo callback concluído pela API e pelo worker deixa os mesmos itens, com os mesmos atributos; reentrega com `providerRequestId` refaz a metade do worker sem chamar, com e sem callback pendente; T11 com vários callbacks pendentes para a mesma consulta e um só resultado; pendência de dados com os paths do snapshot |
| `QuotaAcrossWorkflowsIT`                | T21: 8 pedidos simultâneos de workflows diferentes, com quota 3, fazem 3 chamadas; na seguradora, os excedentes voltam à fila com atraso até a janela seguinte; no enriquecimento, com prazo que não alcança a janela seguinte, concluem com `FAILED` e `QUOTA_EXHAUSTED`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `SimulatorsLambdaFlociIT` (casos do M3) | A beta aceita, agenda o callback e guarda a situação pelas duas referências; a Lambda do `simCallbackSender` entrega o callback da fila assinado com o `callback-secret`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `QuoteFunctionsFlociIT` (7 casos do M3) | T01 com as duas seguradoras no `Map`, cada oferta com a sua evidência; T07; T08 (oferta visível antes de a outra seguradora expirar, versão `PARTIAL`); pendência da beta com e sem oferta da alpha; T11 (callback duplicado, uma oferta e retomada idempotente); T26 (veículo enriquecido anterior a 2000, `NO_OFFER` sem seguradora); seleção pelo ano enriquecido, e não pelo de entrada                                                                                                                                                                                                                                                                                                                                                                                   |
| `QuoteApiFlociIT` (casos do M3)         | T08 (oferta de uma seguradora visível enquanto a outra espera, e mantida depois do `PARTIAL`) e T07 (motivo da recusa ao lado da oferta da outra seguradora)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |

O M4 acrescenta a reconciliação nas duas soluções. As regras estão no §8 e no §9 do
[desenho técnico](plataforma-de-cotacao.md). Os testes do `provider-functions` controlam o relógio e chamam
o `reconciler` direto, sem esperar o agendamento. No `quote-functions`, o `FlociResource` ganhou a fila
`results` com a DLQ e duas máquinas de estado que terminam em `FAILED` e em `TIMED_OUT`, e o
`QuoteFixtures` ganhou a substituição da primeira versão. Nenhuma asserção dos testes do M1 ao M3
mudou.

| Classe                                                                      | O que cobre                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ReconcilerIT`                                                              | T14: consulta da beta aceita e sem callback concluída pela API de situação, sem nova chamada; T16: callback depois da conclusão pela reconciliação guardado como evidência tardia, sem reabrir nada, e callback depois do prazo que conclui a consulta `UNKNOWN` com o próprio resultado; consulta com `202` vencida que refaz a metade do worker e é concluída pelo callback retido, sem ler a situação; `UNKNOWN` sem API de situação abre uma só `UNKNOWN_QUERY` `reconcilable`, resolvida quando a consulta conclui; líder interrompido além do prazo concluído sem chamada, com resposta aos seguidores; caso de borda do M2 (líder `FAILED` que caiu antes do fan-out ainda responde aos seguidores da sua cadeia); callback `RECEIVED` parado processado com o código do worker; callback sem correlação que vira `UNMATCHED` com ocorrência e é processado quando a correlação aparece; outbox além do limiar que abre `STALE_OUTBOX` até ser publicado; evento de ocorrência pendente que não abre outra ocorrência em cadeia; `UNKNOWN` sem API de situação que sai do índice, e as demais tomam a vez; leituras da situação de uma seguradora que não respondeu param no resto do ciclo; `UNKNOWN` concluída pela API de situação que encontra o callback chegado só com o `providerRequestId`; conclusão sem chamada que perde para uma tentativa reservada depois da leitura do `reconciler`; ocorrências abertas e callbacks `UNMATCHED` que nunca se resolvem tomam a vez com os outros; ciclo com todos os deveres e as métricas em EMF |
| `ReconcileWorkerIT`                                                         | `UNKNOWN` de seguradora com API de situação concluída, com o resultado publicado uma só vez; consulta de outra organização respondida como inexistente e intocada; `UNKNOWN` sem API de situação falha a reconciliação e mantém a ocorrência aberta; consulta ainda no prazo não é tocada                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `DeadLetterReprocessingIT`                                                  | T31: mensagem que sempre falha chega à DLQ pela `RedrivePolicy` e é descartada depois de `dlqMaxAttempts`; reenvio de um pedido de seguradora ou de enriquecimento já respondido não repete chamada nem resultado; mensagem além do prazo do pedido é descartada com ocorrência; reconciliação descartada termina como `FAILED` a operação `RUNNING`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `EnrichmentWorkerIT`, `InsurerWorkerIT` e `FunctionInvokerIT` (casos do M4) | O reenvio adiado preserva o `dlqAttempt` da mensagem; o `dlqAttempt` do evento SQS chega ao reenvio adiado                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `CallbackApiFlociIT` (caso do M4)                                           | Callback `UNMATCHED` repetido é associado de novo, em vez de tratado como já processado                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `QuoteFunctionsFlociIT` (casos do M4)                                       | T09 (todas recusam: `NO_OFFER`, sem retry na recusa); T10 (todas indisponíveis: `TECHNICAL_FAILURE`, nunca `NO_OFFER`); T16 (resultado depois do prazo final guardado como evidência, sem reabrir a versão terminal); T17 (resultado da versão substituída fica nela e não toca o resumo atual); T13 (resultado gravado cuja retomada falhou retomado pelo `reconciler`, sem pedido novo); T20 (execução `FAILED`, `TIMED_OUT` ou `ABORTED` finalizada com as ofertas gravadas e `WORKFLOW_FAILED`); `STUCK_VERSION` aberta uma vez e resumo perdido reparado; resumo da versão consolidada cujo `refreshSummary` falhou reparado pelo `reconciler`, e resumo lido antes da consolidação que não rebaixa o `META` terminal; waiter ou versão que sempre falha não bloqueia os outros; waiter expirado sem resultado é o timeout normal e não abre ocorrência; resultado gravado que a execução encerrada nunca recebeu abre `TASK_EXPIRED` uma vez, e o resultado entregue não abre; T31 (resultado que sempre falha chega à DLQ, e o reprocessamento não o duplica; resultado reprocessado depois do prazo da versão volta à retomada e fica como `LATE`); ciclo com todos os deveres e o relatório                                                                                                                                                                                                                                                                                                                                                    |
| `QuoteApiFlociIT` (casos do M4)                                             | T17: nova versão substitui a atual e bloqueia a seleção na antiga; nova versão idempotente e serializada pelo `expectedVersion`; cotação de outra organização é 404, e um leitor não cria versão                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

O M5 acrescenta o contrato integrado e as operações. As regras estão no §3, §8, §9, §10 e §11 do
[desenho técnico](plataforma-de-cotacao.md). No `quote-functions`, o `FlociResource` ganhou a fila `issues`
com a DLQ. No `quote-api`, o `FlociResource` ganhou dois operadores (`olga-operator` na `org-acme` e
`gil-globex-operator` na `org-globex`) e os grupos de operação, e o usuário da Globex ganhou
`perm_offers_select`, para a seleção em cotação de outra organização responder 404 pela organização, e não
pela permissão (T18). Das asserções do M1 ao M4, só mudaram duas contagens do
`FunctionTimingConfigurationTest`: os consumidores SQS passaram de 1 para 2, e a `issues-dlq` entrou no
conjunto de DLQs com alarme. No M6, os casos de params e de tempo desses marcos passaram a rodar também no
stage `dev`, e entraram os casos do M6 listados nos [unitários](#unitários).

| Classe                                           | O que cobre                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `QuoteFunctionsFlociIT` (casos do M5)            | `issue-projector` projeta a ocorrência da provider uma vez por `eventId` e por `revision`; `QueryReconciled` atualiza uma vez a operação da sua organização, sem criar `QUERYREF#`, e o de outra organização é `FOREIGN_ORGANIZATION` e não muda a operação; eventos malformados ou de outra origem não são projetados; a `issues-dlq` é reenviada abaixo do limite e descartada no limite com a organização do evento; a `STUCK_VERSION` é resolvida quando a versão sai do `ACTIVE_VERSION`; uma leitura velha do resumo não rebaixa a fase nem o número de ofertas; o ciclo do `reconciler` roda os deveres do M5 e os reporta |
| `QuoteApiFlociIT` (casos do M5)                  | Listagem das cotações da organização por cursor, com o resumo do `META`; T28 (seleção definitiva, idempotente e que bloqueia novas versões); T18 (oferta expirada, oferta de outra versão e cotação de outra organização recusadas sem intenção gravada); T29 (corrida entre seleção e nova versão com exatamente um vencedor, em 4 rodadas de 6 pedidos, conferindo o `status` e o `code` de cada perdedor); reconciliação de consulta conhecida auditada, enfileirada e restrita à organização                                                                                                                                  |
| `DeadLetterReprocessingIT` (caso do M5)          | O `DEAD_LETTER` de um pedido de reconciliação descartado só leva a consulta que existe e é da organização do pedido, e recebe o `quoteId` gravado na `QUERY`                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| `EnrichmentReuseIT` (casos do M5)                | Seguidor já registrado por outra entrega espera o líder sem segundo registro (`ReuseLedger.Registration.ALREADY_REGISTERED`); entrega que perde a consulta para outra entrega do mesmo pedido não chama nem publica (`QueryLedger.Leading.QUERY_EXISTS`), com um `@MockitoSpyBean` do `QueryLedger` que repassa às chamadas reais                                                                                                                                                                                                                                                                                                 |
| `SimulatorsLambdaFlociIT` (caso do M5)           | O handler da Lambda perfila um veículo do catálogo que está no próprio classpath                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `LambdaZipCatalogIT` (`provider-simulators`, M5) | O zip da Lambda dos simuladores leva o mesmo `catalog.json` do contrato, byte a byte, como o do `quote-api`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

O `mvn verify` dos módulos da provider e da quote roda com o comando abaixo. As contagens da rodada de
fechamento do M5 estão em [Rodada de fechamento do M5](#rodada-de-fechamento-do-m5). No fechamento do M4,
eram 538; na integração do M4, 515; no fechamento do M3, 435; no do M2, 341.

```bash
mvn -B verify -pl provider-solution/domain,provider-solution/api,provider-solution/functions,provider-solution/simulators,quote-solution/domain,quote-solution/api,quote-solution/functions -am
```

## Ponta a ponta

`RequestFlowE2E` chama a API implantada no Floci como um cliente faria:

- **Sucesso:** `202`, execução `SUCCEEDED`, item no DynamoDB com assinatura e tamanho, evento
  `RequestCompleted` na fila de auditoria.
- **Falha:** payload acima do limite, execução `FAILED`, reprocessamento até `requestId-3`, mensagem
  descartada da DLQ e nenhuma execução `requestId-4`.

O Scheduler também roda o `reprocess` a cada 5 minutos, então o teste confere o estado final de cada
fluxo, e não contagens exatas de reinício.

`ReceiptFlowE2E` cobre a `receipt-solution` implantada:

- **Entre as soluções:** envia um pedido pela API da `sample-solution` e espera o comprovante na
  tabela da `receipt-solution`, com o tamanho do payload enviado, o `ReceiptIssued` com a mesma
  assinatura e o mesmo `issuedAt` e o mesmo comprovante na consulta pela API da receipt.
- **Emissão manual:** `POST /receipts` responde `202`, e o comprovante aparece na consulta e no
  `ReceiptIssued`.
- **Consulta sem comprovante:** `404`.

A regra e a fila que capturam o `ReceiptIssued` são criadas pelo teste e removidas ao final.

`QuoteFlowE2E`, no módulo `quote-solution/e2e`, cobre do M1 ao M5 da plataforma de cotação, com o
barramento da plataforma, a `provider-solution` e a `quote-solution` implantados. São 33 testes: os 11
do M1 e do M2, os 7 do M3, os 8 do M4 e os 7 do M5.

**Conferência de contrato no e2e (M5).** Toda troca com a API da quote, nos 33 testes, passa pelo
`ContractClient`, que a confere contra o `contracts/openapi.yaml`, o mesmo arquivo do frontend, com o
`openapi-request-validator-core` (dependência de teste do `quote-e2e`). A resposta é sempre conferida; a
requisição, só nos 2xx, porque os casos de erro a fazem inválida de propósito. Qualquer violação falha o
teste que fez a chamada. O cliente guarda as operações exercitadas e, à parte, as que tiveram resposta 2xx
conferida (`succeeded()`). Foi essa conferência que achou, na integração do M5, os `ISSUE#` antigos do
Floci que a API devolvia com `type: null` ([desenho técnico](plataforma-de-cotacao.md), §4).

- **T01, variante com uma seguradora:** `vehicle-van-2022` com uso `COMMERCIAL` cota só na alpha e
  termina `COMPLETED`, com uma oferta selecionável, a evidência do enriquecimento na quote e na provider
  (`MISS`), a evidência da seguradora e uma única execução `SUCCEEDED`. O T01 do PRD, com duas
  seguradoras, é um teste do M3 (abaixo).
- **T02:** o reenvio com a mesma chave, durante a execução (com o body reordenado e indentado) e depois
  do fim, devolve a mesma cotação, sem execução nova.
- **T03:** a mesma chave com outro body responde `409 IDEMPOTENCY_CONFLICT`, e a cotação original não
  muda.
- **T19** (`t19_scheduledReconcilersRepublishOutboxTheStreamNeverDelivered`): grava, nas tabelas
  implantadas da quote e da provider, um `OUTBOX_PENDING` de 10 minutos atrás que a relay nunca vê. O
  `INSERT` sai com `type = E2E_PROBE`, e um `UPDATE` posterior troca para `OUTBOX`; o filtro do stream
  ignora esse `MODIFY`. O `detail-type` `ReconcilerProbe` não tem regra no barramento. O teste espera o
  `publishedAt` e a remoção do `gsi2pk` nas duas tabelas, o que confirma o `rate(1 minute)` do
  EventBridge Scheduler no Floci nas duas soluções.
- **T23:** veículo salvado termina `INELIGIBLE` (`SALVAGE_VEHICLE`), sem consulta externa.
- **T24:** condutor sem `licenseDate` termina `NEEDS_INFORMATION` (`MISSING_LICENSE_DATE`), com o
  `missingFields` em `risk.principalDriverRef` e sem consulta externa.
- **T04:** a segunda cotação equivalente usa a evidência válida do índice (`HIT`): a provider não abre
  consulta de enriquecimento para ela, e a evidência usada aponta o mesmo `evidenceId`, `queryId`,
  `resultType` e `policyVersion` da primeira (`MISS`). O teste também confere que a `queryKey`
  calculada pelo harness é a mesma gravada pela provider.
- **T05:** com o `REUSE#.reusableUntil` regravado no passado, a cotação equivalente abre outra consulta,
  numa `fencingVersion` maior, e a consulta anterior, com tentativa e evidência, fica como estava.
- **T06:** N cotações equivalentes disparadas juntas (12 por padrão, `-De2e.concurrentQuotes`) terminam
  com uma só consulta de enriquecimento, uma tentativa e o mesmo `evidenceId`: um `MISS`, e os demais
  `HIT` ou `JOIN`. No Floci, os demais tendem a `HIT`, porque o worker processa cerca de 1 mensagem por
  segundo ([desenho técnico](plataforma-de-cotacao.md), §13).
- **T25:** veículo desconhecido do provedor termina `NEEDS_INFORMATION` (`ENRICHMENT_NOT_FOUND`), com o
  `missingFields` em `risk.vehicleRef` e sem seguradora. A evidência usada pode ser `MISS` ou `HIT`,
  porque o `NOT_FOUND` declarado é reutilizável. O `REUSE#` do par é `NOT_FOUND` e aponta para a
  evidência usada, com o mesmo `evidenceId`, `queryId` e `policyVersion`.
- **T27:** provedor de enriquecimento fora do ar termina `TECHNICAL_FAILURE` (`ENRICHMENT_FAILED`), sem
  perfil inventado e sem seguradora; a evidência usada é `MISS`, e não há `REUSE#` para o par, porque
  `FAILED` nunca entra no índice.
- **T01 com duas seguradoras** (`t01_twoInsurersQuoteAndEveryOfferLinksItsEvidence`): a alpha responde
  na chamada e a beta por callback; a versão termina `COMPLETED`, com uma oferta selecionável de cada
  seguradora, cada uma apontando a consulta e a evidência da provider, e o callback da beta `PROCESSED` na
  inbox, com a resposta bruta guardada na evidência.
- **T07:** a beta recusa (`vehicle-beta-declines`) e a alpha oferece; a versão termina `COMPLETED`, com
  uma oferta e o motivo `RISK_NOT_ACCEPTED` no resultado da beta.
- **T08:** a beta aceita e não responde (`vehicle-beta-silent`); a oferta da alpha aparece no GET com a
  fase `QUOTING` e a beta `PROCESSING`, e a versão termina `PARTIAL`, com a mesma oferta e a beta
  `TIMED_OUT` (`STEP_TIMEOUT`). A consulta da beta fica `IN_FLIGHT` ou `UNKNOWN` até a reconciliação, que
  o T14 confere.
- **T11:** a beta entrega o mesmo callback duas vezes (`vehicle-beta-duplicate`); depois da conclusão, o
  harness reenvia o callback assinado (a API responde 200 com `duplicate = true`) e republica o
  `InsurerResultRecorded`. Como o descarte não grava nada, o teste espera a linha
  `result <eventId> already consumed, discarded` no log `/aws/lambda/quote-solution-<stage>-resume`,
  lida pelo `FilterLogEvents` do CloudWatch Logs do Floci (o `quote-e2e` tem a dependência de teste
  `cloudwatchlogs`). A cotação, a consulta e a inbox não mudam, e há um só `CONSUMED` do evento.
- **T12:** o callback antecipado (`vehicle-beta-early`) só com o `providerRequestId` é associado pelo
  encontro no `CORR#`, com as duas metades gravadas, e a oferta chega sem perda. No Floci, ele chega
  depois do `202`; a ordem inversa fica no `CallbackApiFlociIT` e no `InsurerCallbackIT`, este com o
  código real das duas metades.
- **T21:** com o `QUOTA#alpha` da janela atual reservado até sobrarem 2 chamadas e o da seguinte
  esgotado, 5 cotações simultâneas fazem só 2 chamadas: 2 terminam `COMPLETED` e 3 `TECHNICAL_FAILURE`,
  com a alpha `UNAVAILABLE` e `QUOTA_EXHAUSTED`, e nenhuma janela passa do limite.
- **T26:** o veículo enriquecido anterior a 2000 termina `NO_OFFER` com `NO_APPLICABLE_INSURER`, sem
  oferta, resultado, waiter nem consulta de seguradora; a seleção gravou `selectedInsurers` vazio com o
  `selectionRuleVersion`.
- **T09:** com uso `COMMERCIAL`, a alpha é a única seguradora aplicável e recusa
  (`vehicle-alpha-declines`): `NO_OFFER` com `ALL_INSURERS_DECLINED`, motivo `RISK_NOT_ACCEPTED` e uma só
  tentativa, sem retry na recusa.
- **T10:** a alpha, única aplicável, fica indisponível (`vehicle-alpha-down`): `TECHNICAL_FAILURE` com
  `INSURER_TECHNICAL_FAILURE`, nunca `NO_OFFER`, com 1 + `workerRetries` = 3 tentativas, consulta
  `FAILED` e evidência `UNAVAILABLE`.
- **T13:** estado fabricado, como o `QUOTA#` do T21. O harness deixa ilegível o token do waiter da beta
  enquanto ele espera; o `resume` grava `RESUMING`, `resumePayload`, `RESULT`, `OFFER` e `CONSUMED` e cai
  na decifração; depois, o token volta. A versão termina `COMPLETED` com as duas ofertas, o `reconciler`
  retoma o waiter, que sai do índice, não abre `TASK_EXPIRED`, não há `LATE`, e cada seguradora teve uma
  só tentativa.
- **T14:** a beta aceita e fica em silêncio (`vehicle-beta-silent`); a versão termina `PARTIAL`. O log do
  `reconciler` da provider mostra a consulta deixada `UNKNOWN` e concluída pela API de situação, sem
  nova chamada. A consulta fica `COMPLETED`, com 1 tentativa, inbox vazia e a evidência
  `raw/<queryId>/status-0001.json` no S3. Na quote, entra um `LATE` `QUOTED`, e a versão, os resultados,
  as ofertas e o GET não mudam.
- **T16:** o callback da `vehicle-beta-late` chega depois do prazo: a versão termina `PARTIAL`, com a
  beta `STEP_TIMEOUT`, e o callback vira `LATE` `QUOTED`, com 1 tentativa e 1 evidência, sem reabrir a
  cotação.
- **T17:** a v2 é criada com a v1 ainda em andamento: `202`, reenvio idempotente e `409
  VERSION_CONFLICT`. Os `RESULT` da v1 chegam depois do `createdAt` da v2 e ficam na v1; são 4 consultas,
  cada uma com a sua versão, e nenhum `LATE`. A v1 tem `supersededAt`, `allowedActions` vazio e ofertas
  `VERSION_SUPERSEDED`; a v2 oferece `CREATE_VERSION` e `SELECT_OFFER`; as duas execuções terminam
  `SUCCEEDED`.
- **T20:** `StopExecution` com a oferta da alpha gravada e a beta em `PROCESSING`. O `reconciler`
  finaliza a versão: `PARTIAL`, beta `TIMED_OUT` com `WORKFLOW_ABORTED`, ofertas mantidas, versão fora do
  `ACTIVE_VERSION` e do `SUMMARY_PENDING` (o teste espera com prazo a remoção do `gsi2pk`, que o
  `refreshSummary` faz depois do commit da finalização), `WORKFLOW_FAILED` aberta e `QuoteCompleted` no outbox. Como o `StopExecution` do
  Floci não para o interpretador ([desenho técnico](plataforma-de-cotacao.md), §13), o caso despacha a
  cotação cerca de 51 s depois de um ciclo do `reconciler` e tenta até 3 cotações, registrando cada
  tentativa no log; nas rodadas validadas, bastou a primeira. Cada tentativa apaga o reuso do par, para o
  enriquecimento ser `MISS` e a latência até a `dispatch`, medida na tentativa anterior e usada no
  alinhamento, continuar valendo. A tentativa
  descartada, que a continuação do Floci consolidou antes do ciclo, confere as invariantes: as ofertas
  mantidas, nenhum `WORKFLOW_FAILED` e o `QuoteCompleted` com o `outcome` da versão.
- **T31:** confere a `RedrivePolicy` implantada nas filas `results`, `insurer-requests` e
  `reconcile-requests` e põe direto nas DLQs entregas de uma cotação já concluída. Antes de o
  `InsurerResultRecorded` ir à `results-dlq`, a versão é posta fora do prazo; como o prazo da versão não
  conta, ele é reenviado como tentativa 2 e descartado pelo `CONSUMED`; o
  `QueryReconcileRequested` na `reconcile-requests-dlq` é reenviado como tentativa 2, e o
  `QueryReconciled` sai `SUCCEEDED`; o `InsurerQuoteRequested` vencido na `insurer-requests-dlq` é
  descartado com `DEAD_LETTER` e `OperationIssueRaised`. A cotação e a consulta não mudam. A passagem
  real pela `RedrivePolicy`, com as visibilidades do stage, levaria mais de 7 min por mensagem e fica no
  `QuoteFunctionsFlociIT` (`t31_resultThatKeepsFailingReachesTheDlqAndItsReprocessingDoesNotDuplicateIt`) e no
  `DeadLetterReprocessingIT` (`t31_messageThatAlwaysFailsGoesToTheDeadLetterQueueAndIsDiscardedAfterDlqMaxAttempts`).
- **T18** (M5): a Globex recebe 404 na seleção e na leitura da cotação da Acme. A oferta com `validUntil`
  fabricado no passado recebe `409 OFFER_EXPIRED`, e a versão fica só com `CREATE_VERSION`. Nada é
  gravado: nem `SELECTION`, nem `selectionId`, nem idempotência, nem `OfferSelected`. O `validUntil`
  original volta no `finally`.
- **T22** (M5): as 14 operações do contrato são exercitadas, e o T22 afirma o 2xx de 11 delas. As três
  que ele só exercita com 409 ou 404 (`createQuoteVersion`, `reconcileQuery` e `getOperation`) têm o 2xx
  conferido contra o contrato no T17, no T29 e no `m5_manual…`, porque o `ContractClient` confere toda
  resposta. O caso confere o `/v1/me` dos quatro usuários; o catálogo servido igual a `catalog-pages.json`;
  o `PARTIAL` com a pendência da beta igual à fixture; o `NEEDS_INFORMATION` com as duas referências
  (`vehicle-not-found` com `driver-not-found`); os erros 400 e 401 iguais aos de `errors.json`; e também
  401 com token adulterado, 403, 404, `409 VERSION_CONFLICT` e 415.
- **T28** (M5): a mesma oferta devolve a mesma seleção, com a mesma chave ou com outra; outra oferta
  recebe `409 SELECTION_ALREADY_EXISTS`; a chave reutilizada com outra oferta, `409 IDEMPOTENCY_CONFLICT`.
  Ficam uma `SELECTION` e um `OfferSelected`.
- **T29** (M5): 3 seleções e 3 novas versões soltas juntas, por até 3 rodadas; só um lado vence, e, se a
  nova versão vence, a disputa se repete na versão criada. Depois da seleção, a nova versão recebe
  `409 QUOTE_ALREADY_SELECTED`, e o `PARTIAL` com a beta `NEEDS_INFORMATION` sai sem `CREATE_VERSION`. O
  teste afirma que o `NEEDS_INFORMATION` da beta chega depois da seleção, o caso "pendência posterior" do
  PRD, e registra os dois instantes no log.
- **T30** (M5): o viewer recebe 403 na seleção e na reconciliação, e o writer também na reconciliação e
  na listagem de ocorrências. Nada é gravado: nem idempotência, nem `OPERATION#`.
- **Reconciliação manual** (`m5_manualReconciliationOfAConcludedQueryRoundTripsThroughTheProviderAndTheIssueProjector`):
  o teste publica no barramento, em nome da provider, uma `UNKNOWN_QUERY` que aponta a consulta concluída
  da alpha de uma cotação. Ela chega ao `issue-projector`, que grava o `QUERYREF#`, e a listagem mostra
  `RECONCILE` só para quem tem a permissão. O `reconcile` responde `202 QUEUED` com `Location`; o reenvio
  devolve o mesmo id, a mesma chave com outro motivo recebe 409, e a Globex recebe 404. A operação termina
  `SUCCEEDED` nas duas tabelas, e o `QueryReconciled` de volta fica consumido na quote. A listagem só traz
  ocorrências da `org-acme`, sem dado bruto. No `finally`, o teste remove a ocorrência, o `QUERYREF#` e o
  `CONSUMED#` que criou.
- **Catálogo e listagem** (`m5_catalogAndQuoteListingPageWithCursorAndFilters`): as 4 rotas do catálogo,
  paginadas, iguais ao `catalog.json` da organização, com busca sem acento nem maiúscula; a listagem de
  cotações, com `limit=1` e cursor, da mais nova para a mais antiga, com os filtros `outcome`, `phase` e a
  janela inclusiva; a Globex não vê as cotações da Acme; erros 400 de filtro, de intervalo e de cursor.

Cada caso de reuso usa um par veículo/condutor próprio: T01 e T02, `vehicle-van-2022` com
`driver-acme-bruno`; T04, `vehicle-sedan-2019` com `driver-acme-ana`; T05, `vehicle-classic-1998` com
`driver-acme-ana`; T06, `vehicle-classic-1998` com `driver-acme-bruno`. O `vehicle-classic-1998`
termina `NO_OFFER` sem chamar seguradora. Antes de T01, T04, T05 e T06, o harness lê o segredo
`provider-solution/<stage>/query-key-secret` do Floci, calcula a `queryKey` do par como a provider
(HMAC-SHA256 de `organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef`) e
apaga o `REUSE#` e a partição `INFLIGHT#` (`META` e `FOLLOWER#`) dela; as consultas e evidências
anteriores ficam.

Nos casos do M3, o T01 com duas seguradoras usa `vehicle-hatch-2021` com `driver-acme-ana`, com o reuso
do par apagado antes, como no T01 do M1, que continua; os casos da beta (T07, T08, T11 e T12) usam
`driver-acme-bruno`; o T21 usa o `vehicle-van-2022` comercial, que só cota na alpha; o T26 usa
`vehicle-classic-1998` com `driver-acme-ana`, o par do T05, e aceita `HIT` ou `MISS`. O harness lê
também o `provider-solution/<stage>/callback-secret` do Floci local, para o T11 assinar o callback que
reenvia. No T21, ele lê o limite da alpha do `serverless.yml` da provider, grava os dois `QUOTA#alpha` e
os apaga no `finally`, para os casos seguintes terem a quota real da janela. O lado do enriquecimento
do T21 fica no `QuotaAcrossWorkflowsIT`.

Nos casos do M4, cada um usa um par veículo/condutor próprio e apaga o reuso do par no início e no
`finally`: T09, `vehicle-alpha-declines` com `driver-acme-bruno` e uso `COMMERCIAL`; T10,
`vehicle-alpha-down` com `driver-acme-bruno` e uso `COMMERCIAL`; T13, `vehicle-hatch-2021` com
`driver-acme-bruno`; T14, `vehicle-beta-silent` com `driver-acme-ana`; T16, `vehicle-beta-late` com
`driver-acme-bruno`; T17, a v1 com `vehicle-sedan-2019` e `driver-acme-bruno` e a v2 com
`vehicle-beta-declines` e `driver-acme-ana`; T20, `vehicle-beta-late` com `driver-acme-ana`; T31,
`vehicle-van-2022` com `driver-acme-ana` e uso `COMMERCIAL`. T09 e T10 rodam só com a alpha porque
nenhum veículo faz as duas seguradoras recusarem e a beta não tem cenário de indisponibilidade. Os casos
que dependem do `reconciler` (T13, T14, T20 e T31) esperam os ciclos dele com prazo, sem pausa fixa; cada
ciclo pode levar até 70 s no Floci ([desenho técnico](plataforma-de-cotacao.md), §13). O usuário do e2e ganhou o
grupo `perm_offers_select`, para o T17 conferir que só a versão atual oferece `SELECT_OFFER`.

Nos casos do M5, cada um apaga o reuso do par antes e no `finally`: T18, `vehicle-alpha-declines` com
`driver-acme-ana`; T22, `vehicle-beta-needs-info` com `driver-acme-ana` e `vehicle-not-found` com
`driver-not-found`; T28, `vehicle-beta-duplicate` com `driver-acme-ana`; T29, `vehicle-beta-needs-info`
com `driver-acme-bruno`; T30, `vehicle-alpha-down` com `driver-acme-ana`; reconciliação manual,
`vehicle-flaky` com `driver-acme-ana`. O caso do catálogo e da listagem usa o `vehicle-salvage` e o
`driver-no-license`, que terminam sem consulta externa. Dois casos não têm número no PRD e usam o prefixo
`m5_`. A reconciliação manual usa uma `UNKNOWN_QUERY` publicada pelo próprio teste, porque nenhum cenário
do laboratório deixa uma consulta `UNKNOWN` de forma determinística; o resultado conferido é `SUCCEEDED`
("já concluída").

O M5 usa quatro usuários do Cognito, cada um mantido exatamente nos seus grupos, e cria os grupos que
faltarem: `e2e-acme-writer`, o que já existia (`quotes:read`, `quotes:write` e `offers:select`);
`e2e-acme-operator`, com as cinco permissões; `e2e-acme-viewer`, sem `offers:select` nem
`operations:reconcile`; e `e2e-globex-operator`, da `org-globex`, com as cinco permissões, para o 404 dele
vir da organização, e não da permissão.

O usuário de teste é criado pelas APIs administrativas do Cognito (`AdminCreateUser`,
`AdminSetUserPassword` e `AdminAddUserToGroup`, nos grupos da organização e das permissões), e o
access token vem de `InitiateAuth` com `USER_PASSWORD_AUTH`. Quem valida o token é a Lambda da API. O
orçamento de cada espera é lido do bloco `params` do `serverless.yml` da quote, para o teste esperar o
mesmo prazo que a máquina de estado tem: o T01, o T02 e cada cotação do T04 usam a invariante de
orçamento com uma seguradora (70 s no local); o T05, o T25 e o T27, a mesma invariante sem seguradora
(50 s); o T03, o T23 e o T24, o `stepsMarginSeconds` (30 s). Os casos com duas seguradoras (T01 com
duas, T07, T08, T11 e T12) usam a invariante com ceil(2 / `mapConcurrency`) = 1 espera de seguradora
(70 s no local); o T26, a invariante sem seguradora (50 s); cada cotação do T21, a invariante com uma
seguradora mais 1 s por cotação disparada (75 s). O polling segue o `retryAfterSeconds` (3 s). O T06 usa uma espera única, que confere todas as cotações a cada intervalo de polling, de 50 s
mais N segundos (62 s com N = 12), porque o worker do Floci recebe cerca de 1 pedido por segundo. O
T19 depende do agendamento, não da máquina: espera até 3 min, com polling de 5 s. Ele também é coberto
pela integração do `quote-functions`.

Por causa do T19, ao fim de uma rodada do `quote-e2e`, o log dos `reconciler` mostra a republicação de um
item criado 10 minutos antes, como a vista às 23:04 no fechamento do M3. É a sonda do T19, nas duas tabelas,
e não um outbox real atrasado: sem regra para o `ReconcilerProbe`, a republicação não tem efeito duplicado.
Nas duas rodadas completas da integração do M4, cada `reconciler` republicou exatamente esse item, e nenhum
outbox real da provider foi publicado mais de 10 s depois do `createdAt`. Sob carga, a relay da quote chegou
a atrasar 22,4 s, abaixo dos 30 s de `outboxRepublishAfterSeconds`
([desenho técnico](plataforma-de-cotacao.md), §12). Se o atraso passasse do limiar, o `reconciler`
publicaria junto com a relay o mesmo `eventId`, e os consumidores descartariam a repetição (`CONSUMED`,
`workRef`, `ExecutionAlreadyExists`).

O resultado da rodada de fechamento do M5, com os tempos de cada teste, está em
[Rodada de fechamento do M5](#rodada-de-fechamento-do-m5); o do M4, em
[Rodada de fechamento do M4](#rodada-de-fechamento-do-m4). Na rodada de integração do M4, com a máquina
ociosa e sem build em paralelo, os 26 testes do `QuoteFlowE2E` passaram em 747,6 s (12:31,84 min no
comando inteiro). Numa rodada anterior, com a máquina carregada (load de 7,7), 25 de 26 passaram em
885,2 s: o T06 teve 8 das 12 cotações em
`TECHNICAL_FAILURE` com `ENRICHMENT_FAILED`, porque os pedidos `HIT` chegaram depois da espera de 20 s
([desenho técnico](plataforma-de-cotacao.md), §12). A folga do T06 depende de a máquina estar livre. Tempos
dos casos do M4 na rodada que passou:

| Teste | Tempo                                                                                     |
| ----- | ----------------------------------------------------------------------------------------- |
| T09   | 9,3 s                                                                                     |
| T10   | 9,4 s                                                                                     |
| T13   | 55,0 s                                                                                    |
| T14   | 87,6 s                                                                                    |
| T16   | 42,4 s                                                                                    |
| T17   | 16,6 s                                                                                    |
| T20   | 136,9 s (`dispatch` no ciclo do `reconciler` + 50,4 s, `StopExecution` no ciclo + 54,3 s) |
| T31   | 82,6 s                                                                                    |

Na rodada de fechamento do M3, os 18 testes do `QuoteFlowE2E` passaram em 312,0 s (5:15 min no
Maven), sem build em paralelo e sem aquecer funções antes: a cadeia fria da beta cabe na espera de 20 s
da seguradora ([desenho técnico](plataforma-de-cotacao.md), §12). No fechamento do M2, os 11 testes
levaram 191,0 s. Tempos por teste na rodada de fechamento do M3:

| Teste                            | Tempo  |
| -------------------------------- | ------ |
| T01, variante com uma seguradora | 10,3 s |
| T02                              | 9,2 s  |
| T03                              | 3,1 s  |
| T04                              | 18,4 s |
| T05                              | 12,4 s |
| T06 (N = 12)                     | 29,5 s |
| T19                              | 55,3 s |
| T23                              | 3,1 s  |
| T24                              | 9,1 s  |
| T25                              | 6,2 s  |
| T27                              | 6,1 s  |
| T01 com duas seguradoras         | 31,7 s |
| T07                              | 12,2 s |
| T08                              | 24,2 s |
| T11                              | 23,4 s |
| T12                              | 9,3 s  |
| T21                              | 40,8 s |
| T26                              | 6,2 s  |

```bash
docker compose up -d
pnpm test:e2e
```

O `test:e2e` roda o `deploy:local`, descrito em [Implantação no Floci](../README.md#implantação-no-floci),
e depois `mvn -B verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e,quote-solution/e2e`. Para
testar só contra as stacks já implantadas:

```bash
mvn -B verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e,quote-solution/e2e
```

As propriedades `e2e.endpoint` (padrão `http://localhost:4566`) e `e2e.stage` (padrão `local`) dos
`pom.xml` dos módulos `e2e` apontam para outra stack. No `quote-solution/e2e`, o perfil `floci`, ativo por
padrão, passa ao failsafe o `aws.endpointUrl` (`e2e.endpoint`) e as credenciais fixas `test` do Floci, e a
propriedade `e2e.region` (padrão `us-east-1`) define o `aws.region`. O perfil `e2e-aws`
(`mvn -B verify -Pe2e,e2e-aws -pl quote-solution/e2e`) desliga o `floci`, usa o stage `dev` e não passa
endpoint nem credenciais: o SDK usa a região de `e2e.region` e as credenciais da cadeia padrão (`AWS_PROFILE`
ou SSO), e a URL da API é a do `execute-api` da região. O roteiro está na
[implantação na AWS](implantacao-aws-dev.md). A propriedade
`e2e.concurrentQuotes` (padrão 12) define o número de cotações do T06, como em
`-De2e.concurrentQuotes=20`. No Floci, o worker recebe cerca de 1 pedido por segundo, e a espera de
cada pedido na fila conta dentro dos 20 s do enriquecimento. Com 20, a maior espera chegou a 17,1 s, e
numa rodada um `HIT` chegou 0,43 s depois do prazo, com 1 das 20 versões em `TECHNICAL_FAILURE`
([desenho técnico](plataforma-de-cotacao.md), §12). O caso de 100 pedidos concorrentes fica no
`EnrichmentReuseIT`.

## Rodada de fechamento do M5

Números da rodada de verificação do M5, depois das correções da revisão. Este é o único lugar com as
contagens e os tempos dessa rodada: eles valem para ela e podem mudar a cada rodada, com teste novo ou com
a carga da máquina.

Antes do `verify`, todos os fontes de `platform/spring-aws` e dos 7 módulos foram recompilados com
`touch` (comando no início deste documento). Depois dele, as 62 classes de teste tinham relatório
surefire ou failsafe novo, e nenhum `.class` dos 8 módulos tinha "Unresolved compilation".

O `mvn verify` dos 7 módulos (comando em [Integração com Floci](#integração-com-floci)), com o
`spring-aws` que entra pelo `-am`, rodou 626 testes em 3:35 min: nenhuma falha e 1 pulado, o mesmo caso
`dev` do `ServerlessConfigurationTest` do M4. Eram 538 no fechamento do M4; 616 antes das correções da
revisão do M5, que acrescentaram 10 testes.

| Módulo                | Testes                                                                                      |
| --------------------- | ------------------------------------------------------------------------------------------- |
| `spring-aws`          | 5                                                                                           |
| `provider-domain`     | 72                                                                                          |
| `provider-api`        | 19 (7 de contrato e 12 de integração)                                                       |
| `provider-functions`  | 153 (61 unitários, 1 deles pulado, e 92 de integração)                                      |
| `provider-simulators` | 47 (41 unitários e 6 de integração)                                                         |
| `quote-domain`        | 111                                                                                         |
| `quote-api`           | 84 (65 de contrato e 19 de integração, 18 no `QuoteApiFlociIT` e 1 no `LambdaZipCatalogIT`) |
| `quote-functions`     | 135 (92 unitários e 43 de integração)                                                       |
| Total                 | 626 (1 pulado)                                                                              |

Foram feitas 14 provas por mutação nas correções da revisão do M5: cada correção, desfeita no código, fez
falhar o teste que a protege.

O `QuoteFlowE2E` rodou duas vezes:

| Rodada         | Resultado                                                                                                                                                                      | Tempo                        | Carga da máquina                    |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------- | ----------------------------------- |
| Sob carga      | 31 de 33: o T06 terminou pelo timeout de 62 s da espera, com cotações ainda sem `TERMINAL`, e o T20 deu o falso negativo descrito em [Limites conhecidos](#limites-conhecidos) | 1301 s (21:47 min no Maven)  | 2,33 no início, perto de 14 durante |
| Máquina ociosa | 33 de 33                                                                                                                                                                       | 944,6 s (15:49 min no Maven) | 1,80 no início                      |

Na rodada de integração do M5, antes da revisão, os 33 testes passaram em 877,2 s, com carga de 1,89.
Tempos por teste na rodada com a máquina ociosa:

| Teste                               | Tempo   |
| ----------------------------------- | ------- |
| T01, variante com uma seguradora    | 13,7 s  |
| T02                                 | 9,3 s   |
| T03                                 | 3,3 s   |
| T04                                 | 21,8 s  |
| T05                                 | 12,8 s  |
| T06 (N = 12)                        | 50,3 s  |
| T19                                 | 50,7 s  |
| T23                                 | 3,4 s   |
| T24                                 | 3,6 s   |
| T25                                 | 6,3 s   |
| T27                                 | 9,3 s   |
| T01 com duas seguradoras            | 17,2 s  |
| T07                                 | 15,3 s  |
| T08                                 | 27,3 s  |
| T11                                 | 29,7 s  |
| T12                                 | 12,5 s  |
| T21                                 | 35,2 s  |
| T26                                 | 6,3 s   |
| T09                                 | 10,1 s  |
| T10                                 | 12,7 s  |
| T13                                 | 89,5 s  |
| T14                                 | 72,8 s  |
| T16                                 | 42,6 s  |
| T17                                 | 20,3 s  |
| T20                                 | 126,1 s |
| T31                                 | 94,7 s  |
| T18                                 | 16,2 s  |
| T22                                 | 19,6 s  |
| T28                                 | 15,9 s  |
| T29                                 | 43,5 s  |
| T30                                 | 15,8 s  |
| Reconciliação manual (`m5_manual…`) | 23,3 s  |
| Catálogo e listagem (`m5_catalog…`) | 10,7 s  |

| Medida (máquina ociosa)                                               | Valor                                                                                                                                                                                                 |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T06 com N = 12                                                        | 1 `MISS` e 11 `HIT`                                                                                                                                                                                   |
| T06: espera do enriquecimento                                         | Mínimo de 5,5 s, p50 de 17,4 s e máximo de 18,9 s                                                                                                                                                     |
| T06: `POST` até `TERMINAL`                                            | Mínimo de 14,6 s, p50 de 31,9 s e máximo de 37,3 s                                                                                                                                                    |
| T01 com duas seguradoras: `POST` até a primeira oferta visível no GET | 9,2 s (8,9 s pelo `createdAt` da oferta)                                                                                                                                                              |
| T01 com duas seguradoras: `POST` até `TERMINAL`                       | 14,1 s                                                                                                                                                                                                |
| T29: seleção e `NEEDS_INFORMATION` da beta                            | A nova versão venceu as três disputas, e a seleção foi feita na versão 4; o `NEEDS_INFORMATION` chegou 2,9 s depois da seleção (4,8 s na rodada sob carga, em que a seleção venceu a segunda disputa) |

As medições do tempo até a primeira oferta, fria e quentes, estão no §12 do
[desenho técnico](plataforma-de-cotacao.md).

## Rodada de fechamento do M4

Números da rodada de fechamento do M4, que substituem os da rodada de integração.

Antes do `verify`, todos os fontes foram recompilados com `touch` (comando no início deste documento).
Depois dele, as 54 classes de teste dos 7 módulos da provider e da quote tinham relatório novo.

O `mvn verify` dos 7 módulos (comando em [Integração com Floci](#integração-com-floci)), com o
`spring-aws` que entra pelo `-am`, rodou 538 testes: nenhuma falha e 1 pulado, o caso `dev` do
`ServerlessConfigurationTest` que compara o atraso dos callbacks da beta com a espera da seguradora,
porque a quote ainda não declara o stage `dev` (entra no M6).

| Módulo                | Testes                                                                                                                                   |
| --------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `spring-aws`          | 5                                                                                                                                        |
| `provider-domain`     | 72                                                                                                                                       |
| `provider-api`        | 19 (7 de contrato e 12 de integração)                                                                                                    |
| `provider-functions`  | 150 (61 unitários, 1 deles pulado, e 89 de integração, 18 no `ReconcilerIT`, 4 no `ReconcileWorkerIT` e 5 no `DeadLetterReprocessingIT`) |
| `provider-simulators` | 27 (23 unitários e 4 de integração)                                                                                                      |
| `quote-domain`        | 99                                                                                                                                       |
| `quote-api`           | 42 (28 de contrato e 14 de integração, 13 no `QuoteApiFlociIT` e 1 no `LambdaZipCatalogIT`)                                              |
| `quote-functions`     | 124 (88 unitários e 36 no `QuoteFunctionsFlociIT`)                                                                                       |
| Total                 | 538 (1 pulado)                                                                                                                           |

Foram feitas 23 provas por mutação: cada correção e cada decisão nova do M4, desfeita no código, fez
falhar o teste que a protege.

O `QuoteFlowE2E` passou nos 26 testes em 793,8 s (13:17 min no Maven), com a máquina ociosa (load de
1,87).

| Medida                                                 | Valor                                             |
| ------------------------------------------------------ | ------------------------------------------------- |
| T06 com N = 12                                         | 1 `MISS` e 11 `HIT`                               |
| T06: espera do enriquecimento                          | Mínimo de 4,4 s, p50 de 10,8 s e máximo de 12,5 s |
| T06: `POST` até `TERMINAL`                             | Mínimo de 8,4 s, p50 de 18,4 s e máximo de 22,5 s |
| T01 com duas seguradoras: `POST` até a primeira oferta | 6,8 s                                             |
| T01 com duas seguradoras: `POST` até `TERMINAL`        | 16,9 s                                            |

## Backoffice

Os testes do painel ficam fora do Maven e não precisam de stack: as respostas da API e do Cognito saem de
`contracts/fixtures`, só nos testes. Comandos, números e a cobertura de cada caso FT01 a FT17 do PRD de
frontend estão no [README do backoffice](../backoffice/README.md#testes).

| Nível                         | Comando                         | Precisa de             | Tempo aproximado |
| ----------------------------- | ------------------------------- | ---------------------- | ---------------- |
| Unidade e componente (Vitest) | `pnpm --filter backoffice test` | Node.js 24             | Segundos         |
| Navegador (Playwright)        | `pnpm --filter backoffice e2e`  | Chromium do Playwright | Cerca de 2 min   |

## Limites conhecidos

- A resposta `500` da API não tem teste.
- O suporte do `openapi-request-validator` ao OpenAPI 3.1 é parcial; o contrato atual passou, mas
  recursos específicos do 3.1 podem não ser validados.
- O formato da mensagem do SNS é conferido só no teste de integração, onde há um assinante SQS; a
  stack do exemplo não tem assinante.
- O caminho de falha da `receipt-solution` (execução `FAILED` depois das novas tentativas) não tem
  teste; o exemplo também não tem DLQ para ele.
- O T06 do `QuoteFlowE2E` pode falhar com a máquina carregada, como numa rodada da integração do M4 e
  na rodada sob carga da verificação do M5. Mesmo com a máquina ociosa, a folga da espera do
  enriquecimento caiu para 1,1 s no M5 ([Rodada de fechamento do M5](#rodada-de-fechamento-do-m5)). Na
  validação antes do PR, com carga entre 1,7 e 2,4, ele falhou duas vezes seguidas com N=12 (1 e depois 2
  das 12 versões em `TECHNICAL_FAILURE/ENRICHMENT_FAILED`) e passou com `-De2e.concurrentQuotes=8`, em
  38,6 s. Os prazos do stage `local` não mudaram no M6.
- O T20 do `QuoteFlowE2E` depende do comportamento do Floci depois do `StopExecution`; por isso tenta até
  3 cotações e registra cada tentativa no log. Ele classifica a tentativa só pelo `reasonCode` da beta, e
  isso falha sob carga: na rodada sob carga do M5, a continuação do Floci gravou `STEP_TIMEOUT` na beta,
  mas o `reconciler` finalizou a versão antes do `ConsolidateQuote` (`TERMINAL` e `WORKFLOW_FAILED` no
  mesmo instante). A finalização e o `WORKFLOW_FAILED` são legítimos, e o teste acusou "consolidated before
  the reconciler". Decidir pela transição a `TERMINAL` ou pela presença da ocorrência evitaria o falso
  negativo.
- O disparo dos alarmes e a extração das métricas EMF não têm teste no Floci; ficam para o M6.
- O `ContractClient` confere a requisição só nos 2xx; nos erros, só a resposta. O T22 afirma o 2xx de 11
  das 14 operações; o de `createQuoteVersion`, `reconcileQuery` e `getOperation` é conferido em outros
  casos do mesmo `QuoteFlowE2E`.
- A reconciliação manual de ponta a ponta parte de uma `UNKNOWN_QUERY` publicada pelo próprio teste, e não
  de uma consulta `UNKNOWN` real, que nenhum cenário do laboratório produz de forma determinística.
- O 429 está declarado no contrato, mas a API não o emite no MVP; ele não tem teste, e o do API Gateway
  fica para o M6.
- O `TransactionConflict` da corrida do T29 não é exercitado: o Floci serializa as transações
  condicionais.
- O backoffice consome o contrato pelo cliente gerado e usa as fixtures como respostas nos próprios testes
  ([Backoffice](#backoffice)), mas a jornada dele contra a stack implantada ainda não foi registrada.
