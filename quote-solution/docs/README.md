# quote-solution

Solução de cotação de seguros de automóvel em Quarkus: expõe a API de produto, orquestra a cotação de
cada versão na máquina de estado `quote-flow`, pede enriquecimento e cotações à `provider-solution` pelo
barramento da plataforma e espera as respostas por task token. É uma das duas soluções do contexto de
[cotação](../../docs/cotacao.md); o detalhe de implementação está no
[desenho técnico](../../docs/plataforma-de-cotacao.md), e as decisões, nos ADRs
[0013](../../docs/decisoes/0013-barramento-da-plataforma.md),
[0014](../../docs/decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md) e
[0015](../../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Módulos Maven](#módulos-maven)
- [Funções Lambda e papéis](#funções-lambda-e-papéis)
- [Máquina de estado `quote-flow`](#máquina-de-estado-quote-flow)
- [API HTTP](#api-http)
- [Eventos](#eventos)
- [Persistência](#persistência)
- [Filas e DLQs](#filas-e-dlqs)
- [Segurança](#segurança)
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
| API de produto: cotações, versões, ofertas, seleção, catálogo, operações e `/v1/me` | `api`: [`QuoteResource`](../api/src/main/java/com/mmda/serverless/quote/api/QuoteResource.java), [`OperationsResource`](../api/src/main/java/com/mmda/serverless/quote/api/OperationsResource.java), [`CatalogResource`](../api/src/main/java/com/mmda/serverless/quote/api/CatalogResource.java), [`MeResource`](../api/src/main/java/com/mmda/serverless/quote/api/MeResource.java) |
| Gravação durável das mutações, com idempotência e outbox na mesma transação | `api`: [`QuoteStore`](../api/src/main/java/com/mmda/serverless/quote/api/QuoteStore.java), [`OperationsStore`](../api/src/main/java/com/mmda/serverless/quote/api/OperationsStore.java) |
| Orquestração de uma versão | `quote-flow`, definida no [`serverless.yml`](../serverless.yml); passos em [`WorkflowSteps`](../functions/src/main/java/com/mmda/serverless/quote/functions/WorkflowSteps.java) |
| Regras de elegibilidade (`elig-1`) e de seleção de seguradoras (`sel-1`), consolidação | `domain`: [`EligibilityRule`](../domain/src/main/java/com/mmda/serverless/quote/domain/EligibilityRule.java), [`InsurerSelectionRule`](../domain/src/main/java/com/mmda/serverless/quote/domain/InsurerSelectionRule.java), [`Consolidation`](../domain/src/main/java/com/mmda/serverless/quote/domain/Consolidation.java) |
| Decisões da seleção, das ações permitidas e das operações | `domain`: [`Selections`](../domain/src/main/java/com/mmda/serverless/quote/domain/Selections.java), [`QuoteActions`](../domain/src/main/java/com/mmda/serverless/quote/domain/QuoteActions.java), [`Operations`](../domain/src/main/java/com/mmda/serverless/quote/domain/Operations.java) |
| Pedido à provider e espera por task token, com o token cifrado no waiter | `functions`: [`DispatchHandler`](../functions/src/main/java/com/mmda/serverless/quote/functions/DispatchHandler.java), [`TokenCipher`](../functions/src/main/java/com/mmda/serverless/quote/functions/TokenCipher.java) |
| Gravação do resultado e retomada da execução | `functions`: [`ResultResumer`](../functions/src/main/java/com/mmda/serverless/quote/functions/ResultResumer.java) |
| Publicação do outbox e início das execuções | `functions`: [`OutboxPublisher`](../functions/src/main/java/com/mmda/serverless/quote/functions/OutboxPublisher.java) |
| Projeção das ocorrências e das reconciliações da provider | `functions`: [`IssueProjector`](../functions/src/main/java/com/mmda/serverless/quote/functions/IssueProjector.java) |
| Reconciliação a cada minuto | `functions`: [`ReconcilerHandler`](../functions/src/main/java/com/mmda/serverless/quote/functions/ReconcilerHandler.java) e os deveres ([`c4-componente-reconciler.puml`](diagramas/c4-componente-reconciler.puml)) |

### O que não está aqui

| Assunto | Onde fica |
|---|---|
| Chamadas aos fornecedores, reuso de enriquecimento, liderança, quota, callbacks e API de situação | `provider-solution` ([cotação](../../docs/cotacao.md#módulos)) |
| Barramento `platform-<stage>-events` | Stack `platform/events` ([platform/docs](../../platform/docs/README.md#barramento-de-eventos)) |
| Frontend | [`backoffice/`](../../backoffice/README.md), que gera o cliente a partir do [`contracts/openapi.yaml`](../../contracts/openapi.yaml) |
| Emissão de proposta | Fora do MVP: a seleção registra só a intenção de proposta (RN13) |

## Módulos Maven

O parent é o `quarkus-parent` de `platform/` ([ADR 0010](../../docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md)).

| Módulo | `artifactId` | Conteúdo | Pacote da Lambda |
|---|---|---|---|
| `domain` | `quote-domain` | Regras, estados, consolidação, idempotência, chaves e eventos, em Java puro (só `jackson-databind`) | Nenhum; entra nos dois zips |
| `api` | `quote-api` | `quarkus-amazon-lambda-rest`, `quarkus-rest-jackson`, `quarkus-smallrye-jwt` e DynamoDB; o `catalog.json` de `contracts/fixtures` vai para o classpath em `fixtures/` | `api/target/quote-api-lambda.zip` |
| `functions` | `quote-functions` | `quarkus-amazon-lambda` com DynamoDB, EventBridge, Step Functions, Secrets Manager e SQS | `functions/target/quote-functions-lambda.zip`, um zip para as seis funções |
| `e2e` | `quote-e2e` | `QuoteFlowE2E`, os cenários ponta a ponta das duas soluções; só entra no build com `-Pe2e` | Nenhum |

Pacotes Java: `com.mmda.serverless.quote.{domain,api,functions,e2e}`. Os clients do AWS SDK são síncronos,
com o client HTTP CRT ([ADR 0005](../../docs/decisoes/0005-clients-sincronos-com-crt.md)).

## Funções Lambda e papéis

Todas usam `runtime: java21`, `memorySize: 1769` (1 vCPU), o handler
`io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest` e um papel IAM próprio. As seis
funções do zip `quote-functions` escolhem o handler pela variável `QUARKUS_LAMBDA_HANDLER`, que aponta para
um bean `@Named`.

| Função (`serverless.yml`) | `QUARKUS_LAMBDA_HANDLER` → classe | Gatilho | Timeout | Papel |
|---|---|---|---|---|
| `api` | não se aplica (`quarkus-amazon-lambda-rest`) | API Gateway REST, `ANY /v1/{proxy+}` | 29 s | `ApiRole` |
| `workflowSteps` | `workflow-steps` → `WorkflowStepsHandler` | `quote-flow`, `lambda:invoke` | `stepsTimeoutSeconds` (15 s) | `WorkflowStepsRole` |
| `dispatch` | `dispatch` → `DispatchHandler` | `quote-flow`, `lambda:invoke.waitForTaskToken` | `dispatchTimeoutSeconds` (15 s) | `DispatchRole` |
| `resume` | `resume` → `ResumeHandler` | Fila `results`, `batchSize: 1`, `ReportBatchItemFailures` | `resumeTimeoutSeconds` (15 s) | `ResumeRole` |
| `issueProjector` | `issue-projector` → `IssueProjectorHandler` | Fila `issues`, `batchSize: 1`, `ReportBatchItemFailures` | `issueProjectorTimeoutSeconds` (15 s) | `IssueProjectorRole` |
| `outboxRelay` | `outbox-relay` → `OutboxRelayHandler` | Stream da tabela, lotes de 100, só `INSERT` com `type = OUTBOX`, 3 retries, divisão do lote, `onFailure` na `stream-failures` | `relayTimeoutSeconds` (30 s) | `OutboxRelayRole` |
| `reconciler` | `reconciler` → `ReconcilerHandler` | EventBridge Scheduler (`method: scheduler`), `rate(1 minute)`, entrada `{source: schedule}` | `reconcilerTimeoutSeconds` (60 s) | `ReconcilerRole` |

Os passos simples ficam numa só função porque, no Floci, cada função Java vira um contêiner
([§7 do desenho](../../docs/plataforma-de-cotacao.md#7-funções)). O `outboxRelay` e o `resume` leem o JSON cru do
evento ([`LambdaEvents`](../functions/src/main/java/com/mmda/serverless/quote/functions/LambdaEvents.java)), sem os
modelos do `aws-lambda-java-events`.

## Máquina de estado `quote-flow`

Uma execução por versão, com o nome `q-<quoteId>-v<version>` e a entrada `{quoteId, version}`; os dados de
negócio ficam no DynamoDB, e o estado da execução leva só referências. A ordem é `LoadQuoteSnapshot`,
`EvaluateEligibility`, `ResolveEnrichment` (espera por task token), `SelectInsurers`, `QuoteInsurers` (Map
com `MaxConcurrency` 2) e `ConsolidateQuote`, com desvio direto para a consolidação quando a versão não é
elegível, o enriquecimento não é `FOUND` ou nenhuma seguradora se aplica. O `TimeoutSeconds` da máquina
(360 s) é só rede de segurança; a execução encerrada sem consolidar é finalizada pelo `reconciler`.

Diagramas: [`estados-quote-flow.puml`](diagramas/estados-quote-flow.puml) (estados e transições, como no
`serverless.yml`), [`estados-versao.puml`](diagramas/estados-versao.puml) (fases e outcomes) e
[`atividade-consolidacao.puml`](diagramas/atividade-consolidacao.puml) (precedência). O detalhe de cada passo
está no [§6 do desenho](../../docs/plataforma-de-cotacao.md#6-máquina-de-estado-principal), e o orçamento de
tempo, no [§9](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo).

## API HTTP

O contrato é o [`contracts/openapi.yaml`](../../contracts/openapi.yaml) (OpenAPI 3.1, versão `1.0.0`),
compartilhado com o frontend; a pasta [`contracts/quote-solution/`](../../contracts/quote-solution/README.md)
explica por que ele fica na raiz de `contracts/`. Legenda das colunas: **Permissão** é o grupo `perm_*`
exigido (403 antes de qualquer leitura); **Sucesso** é o status do caminho feliz.

| Método | Caminho | Permissão | Sucesso | Classe |
|---|---|---|---|---|
| GET | `/v1/me` | só o token e a organização | 200 | `MeResource` |
| GET | `/v1/catalog/customers`, `/vehicles`, `/drivers`, `/coverage-profiles` | `quotes:write` | 200 | `CatalogResource` |
| POST | `/v1/quotes` | `quotes:write` | 202 + `Location` | `QuoteResource.create` |
| GET | `/v1/quotes` | `quotes:read` | 200 | `QuoteResource.list` |
| POST | `/v1/quotes/{quoteId}/versions` | `quotes:write` | 202 + `Location` | `QuoteResource.createVersion` |
| GET | `/v1/quotes/{quoteId}/versions/{version}` | `quotes:read` | 200 | `QuoteResource.version` |
| GET | `/v1/quotes/{quoteId}/versions/{version}/offers` | `quotes:read` | 200 | `QuoteResource.offers` |
| POST | `/v1/quotes/{quoteId}/versions/{version}/selection` | `offers:select` | 201 | `QuoteResource.select` |
| GET | `/v1/operations/issues` | `operations:read` | 200 | `OperationsResource.issues` |
| POST | `/v1/operations/queries/{queryId}/reconcile` | `operations:reconcile` | 202 + `Location` | `OperationsResource.reconcile` |
| GET | `/v1/operations/{operationId}` | `operations:read` | 200 | `OperationsResource.operation` |

- **Idempotência:** as quatro mutações exigem `Idempotency-Key` (ASCII visível, até 200 caracteres). O
  `requestHash` é o SHA-256 do body em JSON canônico; o mesmo hash devolve a mesma identidade, e outro hash
  responde 409 `IDEMPOTENCY_CONFLICT`. Falha antes da aceitação durável responde 503 `SERVICE_UNAVAILABLE`.
- **Organização:** recurso de outra organização responde 404, como o inexistente; um id de caminho acima de
  128 caracteres também responde 404, sem ler a tabela.
- **Paginação:** `limit` de 1 a 100, padrão 20; o cursor é opaco (base64url). As listagens de cotações e de
  ocorrências leem o `gsi1` da organização do token.
- **Conflitos (409):** `IDEMPOTENCY_CONFLICT`, `VERSION_CONFLICT`, `QUOTE_ALREADY_SELECTED`,
  `VERSION_SUPERSEDED`, `SELECTION_ALREADY_EXISTS` e `OFFER_EXPIRED`.
- **Correlação:** o header `X-Correlation-Id` do cliente, quando bem formado, ou um UUID novo vai na resposta,
  nos erros e nos logs.

Os fluxos com as respostas de erro estão nas sequências [`sequencia-criacao-cotacao.puml`](diagramas/sequencia-criacao-cotacao.puml),
[`sequencia-nova-versao.puml`](diagramas/sequencia-nova-versao.puml), [`sequencia-selecao.puml`](diagramas/sequencia-selecao.puml),
[`sequencia-consulta-versao.puml`](diagramas/sequencia-consulta-versao.puml) e
[`sequencia-reconciliacao-manual.puml`](diagramas/sequencia-reconciliacao-manual.puml); as regras completas, no
[§8](../../docs/plataforma-de-cotacao.md#8-fluxos) e no [§11](../../docs/plataforma-de-cotacao.md#11-autenticação) do desenho.

## Eventos

Contrato: [`contracts/quote-solution/asyncapi.yaml`](../../contracts/quote-solution/asyncapi.yaml) (o que a
quote publica e consome) e [`contracts/provider-solution/asyncapi.yaml`](../../contracts/provider-solution/asyncapi.yaml)
(o que a provider publica). O envelope do `detail` tem `eventId`, `occurredAt` e `schemaVersion` (`"1"`); os
campos de cada evento estão no [§3 do desenho](../../docs/plataforma-de-cotacao.md#3-eventos). Todo evento sai
pelo outbox: a mudança grava o item `OUTBOX#<eventId>` na mesma transação, e o `outboxRelay` publica com
`source = mmda.quote`.

### Publicados

| `detail-type` | Gravado no outbox por | Quando | `eventId` | Consumidor |
|---|---|---|---|---|
| `QuoteAccepted` | `api` | Criação e nova versão | aleatório | Sem regra no laboratório |
| `StartQuoteRequested` (interno) | `api` | Criação e nova versão | aleatório | O `outboxRelay` chama `StartExecution`, sem publicar no barramento |
| `EnrichmentRequested` | `dispatch` | `ResolveEnrichment` | UUIDv5 do `workRef` | `provider-solution` |
| `InsurerQuoteRequested` | `dispatch` | Cada item do Map | UUIDv5 do `workRef` | `provider-solution` |
| `QuoteCompleted` | `workflowSteps` (`consolidate`) ou `reconciler` (finalização) | Versão `TERMINAL` | UUIDv5 de `quoteId\|version\|COMPLETED` | Sem regra no laboratório |
| `OfferSelected` | `api` | Seleção gravada | UUIDv5 do `selectionId` | Sem regra no laboratório |
| `QueryReconcileRequested` | `api` | Reconciliação manual aceita | aleatório | `provider-solution`, que deduplica pelo `operationId` |

### Consumidos

| `detail-type` (`source = mmda.provider`) | Regra | Fila | Função | Efeito |
|---|---|---|---|---|
| `EnrichmentResolved`, `InsurerResultRecorded` | `ProviderResultsRule` | `results` | `resume` | Grava a evidência ou o `RESULT` e a `OFFER` e retoma a execução; fora do prazo, grava `LATE` |
| `QueryReconciled`, `OperationIssueRaised`, `OperationIssueUpdated` | `ProviderIssuesRule` | `issues` | `issueProjector` | Projeta `OPERATION#`, `ISSUE#` e `QUERYREF#` |

Os dois consumidores descartam repetições pelo `CONSUMED#<eventId>`, gravado na mesma transação do efeito; o
`issueProjector` também descarta revisões antigas pela `revision`. Sequências:
[`sequencia-espera-e-retomada.puml`](diagramas/sequencia-espera-e-retomada.puml) e
[`sequencia-projecao-ocorrencias.puml`](diagramas/sequencia-projecao-ocorrencias.puml).

## Persistência

Uma tabela, `quote-solution-<stage>-quotes`, com `pk` e `sk` genéricos, o atributo `type` em todo item,
`PAY_PER_REQUEST` e stream `NEW_IMAGE` ([ADR 0015](../../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md)).
O mapa completo de itens e atributos está no [§4 do desenho](../../docs/plataforma-de-cotacao.md#4-tabela-da-quote-solution)
e em [`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml); as chaves são montadas em
[`QuoteKeys`](../domain/src/main/java/com/mmda/serverless/quote/domain/QuoteKeys.java).

| Índice | Chave | Valores | Quem lê |
|---|---|---|---|
| Tabela | `pk`, `sk` | `QUOTE#<quoteId>` com `META` e `V#<nnnn>[#RESULT\|#OFFER\|#SELECTION\|#EVIDENCE\|#LATE…]`; `IDEMPOTENCY#…`, `WAITER#<workRef>`, `OUTBOX#<eventId>`, `CONSUMED#<eventId>`, `ISSUE#<issueId>`, `QUERYREF#<queryId>`, `OPERATION#<operationId>`, todos com `sk = META` | Todas as funções |
| `gsi1` | `gsi1pk`, `gsi1sk` | `ORG#<organizationId>` + `<createdAt>#<quoteId>` no `META`; `ISSUES#<organizationId>` + `<lastSeenAt>#<issueId>` no `ISSUE#` | `api` (listagens) |
| `gsi2` (esparso) | `gsi2pk`, `gsi2sk` | `ACTIVE_VERSION`, `SUMMARY_PENDING`, `WAITER_RESUMING`, `WAITER_EXPIRED`, `OUTBOX_PENDING`, `ISSUE_OPEN` | `reconciler` |

- **Transações:** cada mutação e cada passo escrevem com condição numa `TransactWriteItems`, com o outbox e,
  nos consumidores, o `CONSUMED#` juntos. O `TransactionConflict` é repetido no código em até 4 tentativas,
  com backoff e jitter ([`Retries`](../domain/src/main/java/com/mmda/serverless/quote/domain/Retries.java)).
- **Concorrência:** seleção e nova versão escrevem o mesmo `META` com a mesma condição, então só uma vence.
  A consolidação é condicionada à `revision` do `V#n`.
- **Resumo do `META`:** `phase`, `outcome` e `offerCount` da versão atual são gravados depois de cada passo,
  em best-effort e sem regredir; o `reconciler` repara divergências pelo `SUMMARY_PENDING`.
- **Ciclos de vida:** [`estados-versao.puml`](diagramas/estados-versao.puml),
  [`estados-resultado-seguradora.puml`](diagramas/estados-resultado-seguradora.puml),
  [`estados-waiter.puml`](diagramas/estados-waiter.puml), [`estados-outbox.puml`](diagramas/estados-outbox.puml),
  [`estados-ocorrencia.puml`](diagramas/estados-ocorrencia.puml) e [`estados-operacao.puml`](diagramas/estados-operacao.puml).

## Filas e DLQs

| Fila | Produtor | Consumidor | `VisibilityTimeout` | `maxReceiveCount` | Falha |
|---|---|---|---|---|---|
| `quote-solution-<stage>-results` | `ProviderResultsRule` | `resume` | 90 s | 5 | `results-dlq` |
| `quote-solution-<stage>-issues` | `ProviderIssuesRule` | `issueProjector` | 90 s | 5 | `issues-dlq` |
| `quote-solution-<stage>-results-dlq` | Redrive da `results` | `reconciler` (sem ESM) | padrão | não se aplica | Retenção de 14 dias |
| `quote-solution-<stage>-issues-dlq` | Redrive da `issues` | `reconciler` (sem ESM) | padrão | não se aplica | Retenção de 14 dias |
| `quote-solution-<stage>-stream-failures` | `onFailure` do ESM do stream | Nenhum | padrão | não se aplica | Retenção de 14 dias; só o alarme a lê |

O reprocessamento segue o [ADR 0007](../../docs/decisoes/0007-dlq-e-reprocessamento.md): o `reconciler` lê a
DLQ, reenvia à fila de origem com o atributo `dlqAttempt` incrementado enquanto a tentativa for menor que
`dlqMaxAttempts` (3) e, no limite ou quando a mensagem não identifica o recurso, a apaga e abre a ocorrência
`DEAD_LETTER`. Na `results-dlq`, o prazo da versão não conta: a retomada grava o resultado tardio como `LATE`.
Um registro descartado do stream só volta pelo `reconciler`, que lê o `OUTBOX_PENDING` na tabela
([§9 do desenho](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo)).

## Segurança

- **Autenticação:** a `api` valida o access token do user pool `quote-solution-<stage>-users` com
  smallrye-jwt (`mp.jwt.verify.issuer = JWT_ISSUER`, JWKS em `<JWT_ISSUER>/.well-known/jwks.json`). O
  [`CognitoAccessTokenAugmentor`](../api/src/main/java/com/mmda/serverless/quote/api/CognitoAccessTokenAugmentor.java)
  recusa ID token: exige `token_use = access` e o `client_id` do app client `quote-solution-<stage>-web`. O
  API Gateway não tem autorizador.
- **Organização e permissões:** a organização vem do único grupo `org_<organizationId>` (com dois, o acesso é
  negado); as permissões, dos grupos `perm_quotes_read`, `perm_quotes_write`, `perm_offers_select`,
  `perm_operations_read` e `perm_operations_reconcile` ([`Permission`](../domain/src/main/java/com/mmda/serverless/quote/domain/Permission.java)).
  O user pool só aceita criação administrativa de usuários.
- **Task token:** nunca sai da quote. A `dispatch` o cifra com AES-256-GCM, com o `workRef` como dado
  associado e a chave derivada por HMAC-SHA256 do segredo `quote-solution/<stage>/token-key`; os eventos levam
  só o `workRef` ([ADR 0014](../../docs/decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md)).
- **Dados nos eventos e ocorrências:** nenhum evento leva token, credencial ou resposta bruta; o
  `safeSummary` das ocorrências também não.
- **IAM:** um papel por função, cada um com uma política `quote-<função>-logs` que libera
  `logs:CreateLogStream` e `logs:PutLogEvents` só no grupo de log da própria função.

| Papel | Permissões, além dos logs |
|---|---|
| `ApiRole` | `GetItem`, `PutItem`, `UpdateItem`, `ConditionCheckItem` e `Query` na tabela; `Query` no `gsi1` |
| `WorkflowStepsRole` | `GetItem`, `PutItem`, `UpdateItem` e `Query` na tabela |
| `DispatchRole` | `GetItem`, `PutItem`, `UpdateItem` e `Query` na tabela; `GetSecretValue` no `token-key`; `states:SendTaskSuccess` na máquina |
| `ResumeRole` | As da `DispatchRole`, mais `ReceiveMessage`, `DeleteMessage` e `GetQueueAttributes` na `results` |
| `IssueProjectorRole` | `GetItem`, `PutItem` e `UpdateItem` na tabela; `ReceiveMessage`, `DeleteMessage` e `GetQueueAttributes` na `issues` |
| `OutboxRelayRole` | `DescribeStream`, `GetRecords` e `GetShardIterator` no stream; `ListStreams` em `*`; `UpdateItem` na tabela; `events:PutEvents` no barramento; `states:StartExecution`; `sqs:SendMessage` na `stream-failures` |
| `ReconcilerRole` | `GetItem`, `PutItem`, `UpdateItem` e `Query` na tabela; `Query` no `gsi2`; `events:PutEvents`; `states:StartExecution` e `states:SendTaskSuccess` na máquina; `DescribeExecution` e `GetExecutionHistory` nas execuções; `GetSecretValue`; `ReceiveMessage` e `DeleteMessage` nas duas DLQs e `SendMessage` na `results` e na `issues`; `lambda:InvokeFunction` só na própria função. Confia também em `scheduler.amazonaws.com`, com `aws:SourceAccount` |

O papel que o `serverless-step-functions` gera para a máquina invocar as funções não está no
`serverless.yml`. O Floci não aplica IAM; o que falta conferir na AWS está no
[§2 do desenho](../../docs/plataforma-de-cotacao.md#2-recursos-por-stack).

## Observabilidade e alarmes

- **Logs:** cada função grava no grupo `/aws/lambda/quote-solution-<stage>-<função>`. Linhas úteis: o
  `reconciler report: {…}` de cada ciclo, com os contadores e os deveres que falharam; o
  `result <eventId> already consumed, discarded` do `resume`, único rastro de um descarte; o
  `message <messageId> failed and will be redelivered` dos consumidores; e o `quote accepted …
  correlationId=…` da `api`.
- **Métricas EMF:** o `reconciler` escreve, a cada ciclo, uma linha JSON no stdout com `ExpiredTasks` e
  `OutboxPendingAgeSeconds`, no namespace `mmda/quote-solution` e com a dimensão `Stage`
  ([`ReconcilerMetrics`](../functions/src/main/java/com/mmda/serverless/quote/functions/ReconcilerMetrics.java)).
- **Alarmes:** os 8 abaixo, chamados `quote-solution-<stage>-<sufixo>`, com período `alarmPeriodSeconds`
  (60 s), 1 período de avaliação, `GreaterThanOrEqualToThreshold`, `TreatMissingData: notBreaching` e
  `AlarmActions` no tópico SNS `quote-solution-<stage>-alarms`. A assinatura por e-mail só existe com o param
  `alarmEmail` preenchido.

| Sufixo | Métrica | Estatística | Limiar |
|---|---|---|---|
| `results-dlq-messages`, `issues-dlq-messages`, `stream-failures-messages` | `AWS/SQS` `ApproximateNumberOfMessagesVisible` | Maximum | `dlqAlarmMessages` (1) |
| `results-dlq-age`, `issues-dlq-age`, `stream-failures-age` | `AWS/SQS` `ApproximateAgeOfOldestMessage` | Maximum | `dlqAlarmAgeSeconds` (300 s) |
| `outbox-pending-age` | `mmda/quote-solution` `OutboxPendingAgeSeconds` | Maximum | `outboxAlarmAgeSeconds` (120 s) |
| `expired-tasks` | `mmda/quote-solution` `ExpiredTasks` | Sum | `expiredTasksAlarmThreshold` (1) |

O `expired-tasks` conta só a ocorrência `TASK_EXPIRED`: um waiter que expira sem resultado é o timeout normal
da etapa e não dispara o alarme. No Floci, os alarmes são criados, mas não disparam
([§13 do desenho](../../docs/plataforma-de-cotacao.md#13-limites-do-floci-240)).

## Configuração

Os valores ficam no bloco `params` do [`serverless.yml`](../serverless.yml): o `default` traz os da AWS, e o
`local`, só o que difere no Floci. A referência de cada param, com os valores por stage, é o
[§12 do desenho](../../docs/plataforma-de-cotacao.md#12-parâmetros-por-stage). Na quote, só três diferem:

| Param | `default` (AWS) | `local` |
|---|---|---|
| `awsEndpointUrl` | `""` (a condição `HasAwsEndpointUrl` tira o `AWS_ENDPOINT_URL` das funções) | `http://floci:4566` |
| `jwtIssuerBase` | `https://cognito-idp.${aws:region}.amazonaws.com` | `http://floci:4566` |
| `insurerTimeoutSeconds` | 90 | 20 |

Os demais params da quote, por assunto: prazo e orçamento (`quoteDeadlineSeconds`, `workflowMarginSeconds`,
`workflowTimeoutSeconds`, `enrichmentTimeoutSeconds`, `mapConcurrency`, `maxInsurers`, `stepsMarginSeconds`);
timeouts das funções (`stepsTimeoutSeconds`, `dispatchTimeoutSeconds`, `resumeTimeoutSeconds`,
`issueProjectorTimeoutSeconds`, `relayTimeoutSeconds`, `reconcilerTimeoutSeconds`); filas
(`resultsVisibilityTimeoutSeconds`, `resultsMaxReceiveCount`, `issuesVisibilityTimeoutSeconds`,
`issuesMaxReceiveCount`); reconciliação (`reconcilerSchedule`, `outboxRepublishAfterSeconds`, `dlqMaxAttempts`,
`stuckVersionMarginSeconds`, `resumingRetryAfterSeconds`); API (`retryAfterSeconds`); alarmes
(`alarmPeriodSeconds`, `dlqAlarmMessages`, `dlqAlarmAgeSeconds`, `outboxAlarmAgeSeconds`,
`expiredTasksAlarmThreshold`, `alarmEmail`). O `FunctionTimingConfigurationTest` confere, nos stages `local` e
`dev`, que todo `${param:...}` tem valor e que as invariantes de tempo valem.

| Variável de ambiente | Origem | Propriedade | Lida por |
|---|---|---|---|
| `AWS_ENDPOINT_URL` | `awsEndpointUrl`, só com `HasAwsEndpointUrl` | SDK | Todas |
| `TABLE_NAME` | `quote-solution-<stage>-quotes` | `quote.table-name` | `api` e functions |
| `EVENT_BUS_NAME`, `STATE_MACHINE_ARN`, `TOKEN_KEY_SECRET_ID`, `MAX_INSURERS`, `OUTBOX_REPUBLISH_AFTER_SECONDS` | Nomes da stack e params | `quote.*` | functions |
| `QUOTE_DEADLINE_SECONDS`, `RETRY_AFTER_SECONDS`, `WORKFLOW_VERSION` (`quote-flow-1`) | Params e `custom.workflowVersion` | `quote.deadline-seconds`, `quote.retry-after-seconds`, `quote.workflow-version` | `api` |
| `RESULTS_QUEUE_URL`, `RESULTS_DLQ_URL`, `ISSUES_QUEUE_URL`, `ISSUES_DLQ_URL`, `DLQ_MAX_ATTEMPTS`, `STUCK_VERSION_MARGIN_SECONDS`, `RESUMING_RETRY_AFTER_SECONDS`, `METRICS_NAMESPACE`, `STAGE` | `Ref` das filas e params | `reconciler.*` | `reconciler` (o zip valida a configuração em toda função) |
| `COGNITO_CLIENT_ID`, `JWT_ISSUER` | `Ref` do client e `Fn::Join` de `jwtIssuerBase` com o user pool | `quote.auth.client-id`, `mp.jwt.verify.issuer` | `api` |
| `QUARKUS_LAMBDA_HANDLER` | Literal por função | `quarkus.lambda.handler` (padrão `workflow-steps`) | functions |

As variáveis das duas primeiras linhas e as do `reconciler` ficam no `environment` do `provider`, para todas
as funções. O `quote.reconciler-batch-size` (50 itens por dever e por ciclo) não tem variável: vale o
`@WithDefault` de [`QuoteFunctionsConfig`](../functions/src/main/java/com/mmda/serverless/quote/functions/QuoteFunctionsConfig.java).

## Rodar localmente no Floci

A implantação de todas as stacks, na ordem, está em
[Implantação no Floci](../../README.md#implantação-no-floci). A quote precisa do barramento
(`deploy:platform`) e, para o fluxo completo, da `provider-solution`.

```bash
docker compose up -d
pnpm install
pnpm deploy:local          # ou, com os pacotes já gerados: pnpm deploy:quote
```

Para chamar a API, crie um usuário nos grupos da organização com as mesmas APIs administrativas do Cognito
que o `QuoteFlowE2E` usa, com o perfil `localstack` do
[README da raiz](../../README.md#perfil-do-aws-cli). A senha precisa seguir a política do pool (12 ou mais
caracteres, com maiúscula, minúscula, número e símbolo).

```bash
AWS="aws --profile localstack"
POOL=$($AWS cognito-idp list-user-pools --max-results 60 \
  --query "UserPools[?Name=='quote-solution-local-users'].Id" --output text)
CLIENT=$($AWS cognito-idp list-user-pool-clients --user-pool-id "$POOL" \
  --query "UserPoolClients[?ClientName=='quote-solution-local-web'].ClientId" --output text)
$AWS cognito-idp admin-create-user --user-pool-id "$POOL" --username ana --message-action SUPPRESS
$AWS cognito-idp admin-set-user-password --user-pool-id "$POOL" --username ana \
  --password '<senha>' --permanent
for group in org_org-acme perm_quotes_read perm_quotes_write perm_offers_select; do
  $AWS cognito-idp admin-add-user-to-group --user-pool-id "$POOL" --username ana --group-name "$group"
done
TOKEN=$($AWS cognito-idp initiate-auth --client-id "$CLIENT" --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME=ana,PASSWORD='<senha>' --query AuthenticationResult.AccessToken --output text)
API_ID=$($AWS apigateway get-rest-apis --query "items[?name=='local-quote-solution'].id" --output text)
BASE="http://localhost:4566/restapis/$API_ID/local/_user_request_"

curl -s -X POST "$BASE/v1/quotes" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"product":"AUTO","applicantRef":"customer-acme-ana","risk":{"vehicleRef":"vehicle-hatch-2021","usage":"PERSONAL","principalDriverRef":"driver-acme-ana"},"coverageProfile":"COMPREHENSIVE","desiredStartDate":"2026-12-01"}'
curl -s "$BASE/v1/quotes/<quoteId>/versions/1" -H "Authorization: Bearer $TOKEN"
```

O `iss` do token emitido pelo Floci usa o host `floci`, o mesmo do `JWT_ISSUER` da Lambda. As referências
válidas de cada organização estão em [`contracts/fixtures/catalog.json`](../../contracts/fixtures/catalog.json).
A console do Floci (http://localhost:4500) mostra a tabela, as filas, as execuções e os logs. O que o Floci não
reproduz está no [§13 do desenho](../../docs/plataforma-de-cotacao.md#13-limites-do-floci-240).

Para remover só a quote do Floci, ela sai antes da `platform/events`, porque as regras dela ficam no
barramento ([ADR 0013](../../docs/decisoes/0013-barramento-da-plataforma.md)):

```bash
(cd quote-solution && AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1 pnpm sls remove --stage local)
```

## Implantar na AWS

O roteiro do stage `dev` (pré-requisitos, segredos, usuários do Cognito, e-mail dos alarmes, implantação,
verificação do que o Floci não reproduz, custos e limpeza) está em
[Implantação e verificação na AWS](../../docs/implantacao-aws-dev.md). No roteiro, a quote sobe depois de
`platform/events` e da `provider-solution`, com o e-mail dos alarmes em
`--param="alarmEmail=<e-mail>"` ([3.2 Implantar](../../docs/implantacao-aws-dev.md#32-implantar)). No `dev`
valem os params `default`.

## Testes

Os níveis, os comandos e o que cada classe cobre estão em [Testes](../../docs/testes.md). Resumo da quote:

| Módulo | Níveis | Onde está o detalhe |
|---|---|---|
| `domain` | Unitários das regras, da consolidação, das decisões e dos identificadores | [Unitários](../../docs/testes.md#unitários) |
| `api` | Contrato contra o `contracts/openapi.yaml` e as fixtures; integração com Floci (`QuoteApiFlociIT`, `LambdaZipCatalogIT`) | [Contrato](../../docs/testes.md#contrato) e [Integração com Floci](../../docs/testes.md#integração-com-floci) |
| `functions` | Unitários, contrato dos eventos contra os dois AsyncAPI, configuração do `serverless.yml` (`FunctionTimingConfigurationTest`) e integração com Floci (`QuoteFunctionsFlociIT`) | [Unitários](../../docs/testes.md#unitários), [Contrato](../../docs/testes.md#contrato) e [Integração com Floci](../../docs/testes.md#integração-com-floci) |
| `e2e` | `QuoteFlowE2E` contra as duas soluções implantadas, no Floci (perfil `floci`, padrão) ou no `dev` (`-Pe2e,e2e-aws`) | [Ponta a ponta](../../docs/testes.md#ponta-a-ponta) |

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas), todos com o tema
[`tema-plataforma.iuml`](../../docs/diagramas/tema-plataforma.iuml). Para renderizar, veja o comando em
[cotação](../../docs/cotacao.md#diagramas).

| Diagrama | O que mostra e como ler |
|---|---|
| [`c4-conteiner.puml`](diagramas/c4-conteiner.puml) | As sete Lambdas, a máquina, a tabela, as filas, o segredo, o Cognito e os alarmes da stack, com o barramento e a provider por fora. Seta tracejada roxa: assíncrono; verde: retomada por `SendTaskSuccess` |
| [`c4-componente-api.puml`](diagramas/c4-componente-api.puml) | Componentes da Lambda `api`: resources (laranja), segurança, validação e montagem (azul), acesso ao DynamoDB (roxo) e a `quote-domain` (verde) |
| [`c4-componente-workflow.puml`](diagramas/c4-componente-workflow.puml) | Handlers do zip `quote-functions` (laranja) e as classes que cada um usa, com as `Disposition` e os `Result` de cada consumidor |
| [`c4-componente-reconciler.puml`](diagramas/c4-componente-reconciler.puml) | Os deveres do `reconciler`, numerados na ordem em que o `ReconcilerHandler` os chama, e o código reaproveitado de outras Lambdas (roxo) |
| [`estados-quote-flow.puml`](diagramas/estados-quote-flow.puml) | A `quote-flow` como está no `serverless.yml`: tarefas, `Choice` (losango), o Map e os `Catch` (seta vermelha), com os timeouts |
| [`estados-versao.puml`](diagramas/estados-versao.puml) | Fases da versão e os outcomes dentro de `TERMINAL`, com os `outcomeReason` de cada um; tracejado vermelho: finalização pelo `reconciler` |
| [`estados-resultado-seguradora.puml`](diagramas/estados-resultado-seguradora.puml) | `InsurerStatus` do `RESULT`: quem cria, quem passa a `PROCESSING` e quem encerra |
| [`estados-waiter.puml`](diagramas/estados-waiter.puml) | Ciclo do waiter (`WAITING`, `RESUMING`, `DONE`, `EXPIRED`) e os índices `WAITER_RESUMING` e `WAITER_EXPIRED` |
| [`estados-outbox.puml`](diagramas/estados-outbox.puml) | Estados implícitos do item de outbox, falhas transitórias e permanentes e a republicação (azul tracejado) |
| [`estados-ocorrencia.puml`](diagramas/estados-ocorrencia.puml) | `OPEN` e `RESOLVED` das ocorrências da quote e das projetadas da provider |
| [`estados-operacao.puml`](diagramas/estados-operacao.puml) | Reconciliação manual na quote: `QUEUED` e o resultado projetado do `QueryReconciled` |
| [`atividade-consolidacao.puml`](diagramas/atividade-consolidacao.puml) | Precedência do `Consolidation.consolidate`, de cima para baixo; a cor da ação é o tipo de desfecho |
| [`atividade-selecao.puml`](diagramas/atividade-selecao.puml) | Ordem das decisões de `Selections.decide` e a resposta HTTP de cada uma |
| [`sequencia-criacao-cotacao.puml`](diagramas/sequencia-criacao-cotacao.puml) | `POST /v1/quotes`: validação, transação, respostas e publicação pelo outbox |
| [`sequencia-execucao-versao.puml`](diagramas/sequencia-execucao-versao.puml) | Uma versão do `RECEIVED` ao `TERMINAL`, com alpha e beta em paralelo no Map |
| [`sequencia-espera-e-retomada.puml`](diagramas/sequencia-espera-e-retomada.puml) | `dispatch`, pedido à provider, `resume` (prazo, `LATE`, `DUPLICATE`, descarte) e o `Catch` do timeout |
| [`sequencia-nova-versao.puml`](diagramas/sequencia-nova-versao.puml) | `POST .../versions`: condição do `META` e os 409 `VERSION_CONFLICT` e `QUOTE_ALREADY_SELECTED` |
| [`sequencia-selecao.puml`](diagramas/sequencia-selecao.puml) | `POST .../selection`: idempotência, decisão, transação de cinco ações e a releitura numa corrida |
| [`sequencia-consulta-versao.puml`](diagramas/sequencia-consulta-versao.puml) | `GET` da versão e das ofertas, com `allowedActions`, `unavailabilityReason` e `revision` derivados na leitura |
| [`sequencia-reconciliacao-manual.puml`](diagramas/sequencia-reconciliacao-manual.puml) | Pedido de reconciliação, execução na provider, projeção do `QueryReconciled` e `GET` da operação |
| [`sequencia-projecao-ocorrencias.puml`](diagramas/sequencia-projecao-ocorrencias.puml) | Fila `issues`, `issueProjector`, `issues-dlq` e a listagem `GET /v1/operations/issues` |
| [`sequencia-reconciler.puml`](diagramas/sequencia-reconciler.puml) | Um ciclo do `reconciler`, dever a dever, com as decisões de cada um |
| [`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml) | Os 14 tipos de item com `pk`, `sk`, atributos e valores de `gsi1` e `gsi2`; pacote por assunto |
| [`classes-dominio-versao.puml`](diagramas/classes-dominio-versao.puml) | `quote-domain`: snapshot, regras `elig-1` e `sel-1`, consolidação e os enums da versão |
| [`classes-dominio-decisoes.puml`](diagramas/classes-dominio-decisoes.puml) | `quote-domain`: decisões da API e do `reconciler`, chaves, eventos e utilitários |
| [`classes-api.puml`](diagramas/classes-api.puml) | `quote-api`: resources, segurança, validação, stores e montagem das respostas |
| [`classes-funcoes.puml`](diagramas/classes-funcoes.puml) | `quote-functions`: handlers, consumidores, deveres do `reconciler` e infraestrutura compartilhada |
| [`casos-de-uso.puml`](diagramas/casos-de-uso.puml) | Casos de uso por ator, com a rota e a permissão de cada um, e o processamento assíncrono |
| [`implantacao-floci.puml`](diagramas/implantacao-floci.puml) | Stage `local`: o Floci com os serviços emulados (vermelho: limite conhecido) e um contêiner por Lambda |
| [`implantacao-aws.puml`](diagramas/implantacao-aws.puml) | Stage `dev`: as três stacks na conta, com o que falta conferir na AWS em vermelho |

## Divergências conhecidas

1. **`ListStreams` em `*`.** O `OutboxRelayRole` libera `dynamodb:ListStreams` em `*`; a restrição a
   `table/<tabela>/stream/*` fica para conferir na AWS (M6).
2. **Ocorrências que não se resolvem.** `WORKFLOW_FAILED`, `TASK_EXPIRED` e o `DEAD_LETTER` da quote ficam
   `OPEN`; não há reconhecimento manual no MVP, e a listagem com `status=OPEN` cresce com elas (decisão do M5).
3. **429 declarado e não emitido.** O contrato declara 429 com `Retry-After` em todas as rotas; a API não o
   emite no MVP, e na AWS ele pode vir só do throttling do API Gateway.
