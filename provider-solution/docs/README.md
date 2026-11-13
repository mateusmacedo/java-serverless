# provider-solution

Solução de fornecedores da plataforma de cotação de seguros, em Spring. Ela recebe pelo barramento da
plataforma os pedidos da `quote-solution`, consulta o provedor de enriquecimento e as seguradoras (simulados
na própria stack), recebe os callbacks da seguradora assíncrona e devolve resultados e ocorrências como
eventos. O desenho completo está no [desenho técnico da cotação](../../docs/plataforma-de-cotacao.md); as
decisões, nos ADRs [0013](../../docs/decisoes/0013-barramento-da-plataforma.md),
[0014](../../docs/decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md) e
[0015](../../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md); os contratos, em
[`contracts/provider-solution/`](../../contracts/provider-solution/README.md). O índice geral da
documentação é o [docs/README.md](../../docs/README.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Funções Lambda e papéis](#funções-lambda-e-papéis)
- [API HTTP](#api-http)
- [Eventos](#eventos)
- [Persistência](#persistência)
- [Filas e DLQs](#filas-e-dlqs)
- [Segurança e IAM](#segurança-e-iam)
- [Observabilidade e alarmes](#observabilidade-e-alarmes)
- [Configuração](#configuração)
- [Rodar localmente no Floci](#rodar-localmente-no-floci)
- [Implantar na AWS](#implantar-na-aws)
- [Testes](#testes)
- [Diagramas](#diagramas)
- [Divergências conhecidas](#divergências-conhecidas)

## Propósito e responsabilidades

A provider é a única solução que fala com fornecedores externos. Ela nunca repete uma chamada que pode ter
chegado ao fornecedor, grava cada resultado com o evento que o anuncia na mesma transação e deixa para a
reconciliação o que ficou sem resposta.

| Responsabilidade | Onde fica | Detalhe |
|---|---|---|
| Enriquecimento com reuso: evidência válida (`HIT`), espera da consulta equivalente em voo (`JOIN`) ou liderança com lease e `fencingVersion` (`MISS`) | [`EnrichmentRequestHandler`](../functions/src/main/java/com/mmda/serverless/provider/functions/EnrichmentRequestHandler.java), [`EnrichmentResolutions`](../functions/src/main/java/com/mmda/serverless/provider/functions/EnrichmentResolutions.java), [`ReusePlan`](../domain/src/main/java/com/mmda/serverless/provider/domain/enrichment/ReusePlan.java) | [§8 Fluxos](../../docs/plataforma-de-cotacao.md#8-fluxos) |
| Cotação na seguradora síncrona (alpha) e na assíncrona (beta, com `202` e callback) | [`InsurerRequestHandler`](../functions/src/main/java/com/mmda/serverless/provider/functions/InsurerRequestHandler.java), [`InsurerProtocol`](../domain/src/main/java/com/mmda/serverless/provider/domain/insurer/InsurerProtocol.java) | [§8 Fluxos](../../docs/plataforma-de-cotacao.md#8-fluxos) |
| Prazo, retry e quota por provedor numa janela de 1 min comum a todos os workflows | [`RetryPolicy`](../domain/src/main/java/com/mmda/serverless/provider/domain/RetryPolicy.java), [`QuotaWindow`](../domain/src/main/java/com/mmda/serverless/provider/domain/QuotaWindow.java), [`QueryLedger`](../functions/src/main/java/com/mmda/serverless/provider/functions/QueryLedger.java) | [§9 Falhas](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo) |
| Callbacks assinados: inbox, correlação pelo `clientReference` ou pelo encontro no `CORR#` | [`CallbackController`](../api/src/main/java/com/mmda/serverless/provider/api/CallbackController.java), [`CallbackInbox`](../api/src/main/java/com/mmda/serverless/provider/api/CallbackInbox.java), [`PendingCallbacks`](../functions/src/main/java/com/mmda/serverless/provider/functions/PendingCallbacks.java) | [§8 Fluxos](../../docs/plataforma-de-cotacao.md#8-fluxos) |
| Publicação dos eventos pelo outbox | [`OutboxRelayFunction`](../functions/src/main/java/com/mmda/serverless/provider/functions/OutboxRelayFunction.java), [`OutboxPublisher`](../functions/src/main/java/com/mmda/serverless/provider/functions/OutboxPublisher.java) | [§7 Relay de outbox](../../docs/plataforma-de-cotacao.md#relay-de-outbox-as-duas-soluções) |
| Reconciliação a cada minuto, reprocessamento das DLQs e ocorrências operacionais | [`ReconcilerFunction`](../functions/src/main/java/com/mmda/serverless/provider/functions/ReconcilerFunction.java) e os deveres que ela chama | [§9 Falhas](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo) |
| Reconciliação manual pedida por um operador da quote | [`ReconcileRequestHandler`](../functions/src/main/java/com/mmda/serverless/provider/functions/ReconcileRequestHandler.java), [`QueryReconciliation`](../functions/src/main/java/com/mmda/serverless/provider/functions/QueryReconciliation.java) | [§8 Fluxos](../../docs/plataforma-de-cotacao.md#8-fluxos) |
| Fornecedores simulados do laboratório | [`provider-simulators`](../simulators/src/main/java/com/mmda/serverless/provider/simulators) | [§10 Simuladores](../../docs/plataforma-de-cotacao.md#10-simuladores-e-cenários) |

Fica fora da provider, na `quote-solution`: a API de produto, a máquina de estado, o task token, a
seleção de seguradoras e a consolidação das ofertas
([ADR 0014](../../docs/decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md)).

| Módulo | `artifactId` | Conteúdo |
|---|---|---|
| `domain` | `provider-domain` | Regras puras, sem SDK AWS: estado da consulta, identificadores, reuso e liderança (`enrichment`), protocolo das seguradoras e callbacks (`insurer`), ocorrências e DLQs (`operations`) |
| `api` | `provider-api` | Função `api`: Spring MVC atrás do `serverless-java-container` |
| `functions` | `provider-functions` | Workers, `outboxRelay` e `reconciler`, com Spring Cloud Function |
| `simulators` | `provider-simulators` | Função `simulators` (Spring MVC) e `simCallbackSender` (handler sem Spring) |

A provider não tem módulo `e2e`: os cenários ponta a ponta ficam no `quote-e2e`, que exercita as duas
soluções implantadas ([§1 Módulos](../../docs/plataforma-de-cotacao.md#1-módulos)).

## Funções Lambda e papéis

Todas as funções usam `runtime: java21` e `memorySize: 1769`, declarados no `provider` do
[`serverless.yml`](../serverless.yml). As funções do `provider-functions` usam o `FunctionInvoker` do Spring
Cloud Function, com a função escolhida por `SPRING_CLOUD_FUNCTION_DEFINITION` e `lazy-initialization`
ligado. O formato dos zips está em [Empacotamento](../../docs/arquitetura.md#empacotamento).

| Função | Entrada | Gatilho | Timeout | Papel IAM |
|---|---|---|---|---|
| `api` | `provider-api`, `StreamLambdaHandler` | API Gateway `POST /v1/providers/{provider}/callbacks` | 29 s | `CallbacksApiRole` |
| `simulators` | `provider-simulators`, `StreamLambdaHandler` | API Gateway `ANY /sim/{proxy+}` | 29 s | `SimulatorsRole` |
| `simCallbackSender` | `provider-simulators`, `SimCallbackSenderHandler` | Fila `sim-callbacks`, lote 1, `ReportBatchItemFailures` | 15 s | `SimCallbackSenderRole` |
| `enrichmentWorker` | `provider-functions`, `enrichmentWorker` | Fila `enrichment-requests`, lote 1, `ReportBatchItemFailures` | 25 s | Compartilhado |
| `insurerWorker` | `provider-functions`, `insurerWorker` | Fila `insurer-requests`, lote 1, `ReportBatchItemFailures` | 25 s | Compartilhado |
| `reconcileWorker` | `provider-functions`, `reconcileWorker` | Fila `reconcile-requests`, lote 1, `ReportBatchItemFailures` | 25 s | Compartilhado |
| `outboxRelay` | `provider-functions`, `outboxRelay` | Stream da tabela de consultas, lote de 100, só `INSERT` com `type = OUTBOX` | 60 s | Compartilhado |
| `reconciler` | `provider-functions`, `reconciler` | EventBridge Scheduler, `rate(1 minute)`, entrada `{maxItems: 100}` | 60 s | Compartilhado |

Os timeouts de 15 s e 25 s vêm dos params `simCallbackSenderTimeoutSeconds` e `workerTimeoutSeconds`. A
lista detalhada de gatilhos e a forma de entrada de cada módulo estão no
[§7 Funções](../../docs/plataforma-de-cotacao.md#provider-solution).

## API HTTP

O contrato do callback é o [OpenAPI da provider](../../contracts/provider-solution/openapi.yaml)
(versão `0.1.0`, tag `retornos-de-seguradoras`). As rotas `/sim/...` são o fornecedor simulado e não têm
contrato próprio; o comportamento de cada cenário está no
[§10](../../docs/plataforma-de-cotacao.md#10-simuladores-e-cenários).

| Método | Caminho | Função | Autenticação | Respostas |
|---|---|---|---|---|
| `POST` | `/v1/providers/{provider}/callbacks` | `api` | `X-Signature: sha256=<hex do HMAC-SHA256 do corpo>`, com o `callback-secret` | 200, 202, 400, 401, 404, 413, 415, 503 |
| `POST` | `/sim/enrichment/profiles` | `simulators` | Nenhuma | 200 com o perfil, 400, 404 com `notFoundRefs`, 503, 504 |
| `POST` | `/sim/insurers/alpha/quotes` | `simulators` | Nenhuma | 200 com oferta ou recusa, 400, 503 |
| `POST` | `/sim/insurers/beta/quotes` | `simulators` | Nenhuma | 202 com `providerRequestId`, 400 |
| `GET` | `/sim/insurers/beta/quotes/{reference}` | `simulators` | Nenhuma | 200 com `PROCESSING` ou `COMPLETED`, 404 |

No callback, o [`CallbackController`](../api/src/main/java/com/mmda/serverless/provider/api/CallbackController.java)
confere nesta ordem, antes de gravar qualquer coisa:

1. a assinatura sobre os bytes recebidos (401 `INVALID_SIGNATURE`);
2. o provedor do caminho, que precisa estar em `callbacks.providers`, hoje só `beta` (404 `UNKNOWN_PROVIDER`);
3. o tamanho, até 256 KiB, para caber no item `INBOX#` (413 `CALLBACK_TOO_LARGE`);
4. o envelope: objeto JSON com `externalEventId`, `providerRequestId` ou `clientReference` e `result` (400 `INVALID_CALLBACK`).

Depois disso, 200 `PROCESSED` quer dizer resultado gravado (com `duplicate = true` na repetição), e 202
`RECEIVED`, callback retido na inbox à espera da metade do worker no `CORR#`. Uma falha da AWS antes de a
inbox ficar consistente responde 503, e o fornecedor reenvia o mesmo callback. A tabela completa de
respostas está no [§8](../../docs/plataforma-de-cotacao.md#8-fluxos).

## Eventos

Todos os eventos passam pelo barramento `platform-{stage}-events`, criado por `platform/events`
([ADR 0013](../../docs/decisoes/0013-barramento-da-plataforma.md)). O envelope, os schemas e os exemplos
estão no [AsyncAPI da provider](../../contracts/provider-solution/asyncapi.yaml) (versão `0.5.0`); os
pedidos consumidos, no [AsyncAPI da quote](../../contracts/quote-solution/asyncapi.yaml).

### Consumidos

| Evento (`source` `mmda.quote`) | Regra no `serverless.yml` | Fila | Consumidor | Deduplicação |
|---|---|---|---|---|
| `EnrichmentRequested` | `EnrichmentRequestedRule` | `enrichment-requests` | `enrichmentWorker` | Pelo `workRef`: `QUERY#` derivada dele, `CONSUMED#` com o UUIDv5 dele e `FOLLOWER#` da cadeia |
| `InsurerQuoteRequested` | `InsurerQuoteRequestedRule` | `insurer-requests` | `insurerWorker` | Pela `QUERY#` derivada do `workRef` |
| `QueryReconcileRequested` | `QueryReconcileRequestedRule` | `reconcile-requests` | `reconcileWorker` | Pelo `OPERATION#<operationId>` |

### Publicados

Todo evento nasce de um item `OUTBOX#<eventId>` gravado na transação do efeito e sai pela `outboxRelay`
ou, se ficou pendente, pela republicação do `reconciler`, com `source` `mmda.provider`. A entrega é pelo
menos uma vez, e o `eventId` é determinístico (UUIDv5).

| Evento | Quem grava o outbox | `eventId` (UUIDv5 de) | Fila da quote |
|---|---|---|---|
| `EnrichmentResolved` | `enrichmentWorker` (`HIT`, `JOIN`, `MISS` e fan-out), `reconciler` (fan-out pendente e consulta interrompida), `reconcileWorker` (consulta interrompida) | `queryId\|workRef` | `results` |
| `InsurerResultRecorded` | `insurerWorker`, `api` (callback), `reconciler` e `reconcileWorker` (prazo ou API de situação) | `queryId` | `results` |
| `QueryReconciled` | `reconcileWorker`; `reconciler` quando descarta o pedido da DLQ | `operationId\|revision` | `issues` |
| `OperationIssueRaised` e `OperationIssueUpdated` | `reconciler` e `reconcileWorker` | `issueId\|revision` | `issues` |

Nenhum evento leva credencial, CPF ou resposta bruta: a resposta do fornecedor fica no bucket de payloads, e
o evento leva só a chave (`rawResponseRef`). A descrição de cada campo e de cada desfecho está no
[§3 Eventos](../../docs/plataforma-de-cotacao.md#3-eventos).

## Persistência

| Recurso | Nome | Chave e recursos |
|---|---|---|
| Tabela de consultas | `provider-solution-{stage}-queries` | `pk` e `sk` (string); índice `gsi2` (`gsi2pk`, `gsi2sk`, projeção total); stream `NEW_IMAGE`; TTL em `ttl`; sob demanda |
| Tabela dos simuladores | `provider-solution-{stage}-simulators` | Só `pk`; TTL em `ttl`; acesso só da Lambda `simulators` |
| Bucket de respostas brutas | `provider-solution-{stage}-payloads` | `raw/<queryId>/<nnnn>.json` (resposta síncrona), `raw/<queryId>/callback-<externalEventId>.json` e `raw/<queryId>/status-<nnnn>.json` (API de situação) |

A tabela de consultas guarda tipos de item distintos pelo prefixo da chave e pelo atributo `type`. Os
atributos de cada item e as regras de identificadores (`queryId`, `evidenceId`, `issueId`, `queryKey`)
estão no [§5](../../docs/plataforma-de-cotacao.md#5-tabela-da-provider-solution) e no diagrama
[`modelo-dynamodb`](diagramas/modelo-dynamodb.puml).

| Item (`type`) | `pk` | `sk` | Papel |
|---|---|---|---|
| `QUERY` | `QUERY#<queryId>` | `META` | Consulta a um fornecedor e o estado dela |
| `ATTEMPT` | `QUERY#<queryId>` | `ATTEMPT#<nnnn>` | Tentativa, gravada antes do HTTP |
| `EVIDENCE` | `QUERY#<queryId>` | `EVIDENCE#<evidenceId>` | Resultado imutável, inclusive o de falha |
| `FANOUT` | `QUERY#<queryId>` | `FANOUT` | Fan-out do líder ainda por terminar |
| `REUSE` | `REUSE#<queryKey>` | `META` | Índice de reuso do enriquecimento |
| `INFLIGHT` | `INFLIGHT#<queryKey>` | `META` | Liderança e lápide da cadeia |
| `FOLLOWER` | `INFLIGHT#<queryKey>` | `FOLLOWER#<versão com 10 dígitos>#<workRef>` | Seguidor à espera do líder |
| `INBOX` | `INBOX#<provider>#<externalEventId>` | `META` | Callback recebido |
| `CORRELATION` | `CORR#<provider>#<providerRequestId>` | `META` | Encontro das duas metades (api e worker) |
| `QUOTA` | `QUOTA#<provider>#<credentialRef>#<janela>` | `META` | Contador da janela de 1 min |
| `OUTBOX` | `OUTBOX#<eventId>` | `META` | Evento a publicar |
| `CONSUMED` | `CONSUMED#<eventId>` | `META` | Pedido já respondido |
| `ISSUE` | `ISSUE#<issueId>` | `META` | Ocorrência operacional |
| `OPERATION` | `OPERATION#<operationId>` | `META` | Reconciliação manual |

O índice `gsi2` é esparso: só tem o trabalho pendente, e o `reconciler` o lê em ordem de `gsi2sk`, até
`maxItems` por dever. O item que continua pendente depois de uma conferência recebe no `gsi2sk` o instante
dela e vai para o fim do índice.

| `gsi2pk` | `gsi2sk` | Quem lê |
|---|---|---|
| `QUERY_IN_FLIGHT` | `deadlineAt` do pedido | `reconciler`, para achar as consultas vencidas |
| `QUERY_UNKNOWN` | Instante em que ficou `UNKNOWN`, depois o da última conferência | `reconciler` |
| `OUTBOX_PENDING` | `createdAt`, depois o da última falha permanente | `reconciler` (republicação e `STALE_OUTBOX`) |
| `INBOX_RECEIVED` | `receivedAt` | `reconciler` (inbox parada) |
| `INBOX_UNMATCHED` | `receivedAt`, depois o da última revisão | `reconciler` e o alarme de callbacks sem correlação |
| `FANOUT_PENDING` | `concludedAt` | `reconciler` (fan-out interrompido) |
| `ISSUE_OPEN` | `lastSeenAt`, depois o da última revisão | `reconciler` (só os tipos que se resolvem sozinhos) |

Toda gravação que muda o estado é uma `TransactWriteItems` com condições, e o `TransactionConflict` é
repetido até 3 vezes pelo [`Transactions`](../functions/src/main/java/com/mmda/serverless/provider/functions/Transactions.java),
nos dois módulos que escrevem. O modelo de uma tabela por solução e do outbox por stream é o do
[ADR 0015](../../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md).

## Filas e DLQs

| Fila | Quem envia | Consumidor | Visibilidade | `maxReceiveCount` | DLQ |
|---|---|---|---|---|---|
| `enrichment-requests` | Regra `EnrichmentRequested`; reenvio adiado do worker | `enrichmentWorker` | 150 s | 3 | `enrichment-requests-dlq` |
| `insurer-requests` | Regra `InsurerQuoteRequested`; reenvio adiado do worker | `insurerWorker` | 150 s | 3 | `insurer-requests-dlq` |
| `reconcile-requests` | Regra `QueryReconcileRequested` | `reconcileWorker` | 150 s | 3 | `reconcile-requests-dlq` |
| `sim-callbacks` | `simulators` (beta), com `DelaySeconds` | `simCallbackSender` | 90 s | 5 | `sim-callbacks-dlq` |
| `stream-failures` | Destino `onFailure` do stream da `outboxRelay` | Nenhum | Padrão do SQS | Não se aplica | Não tem |

Os nomes completos levam o prefixo `provider-solution-{stage}-`. As DLQs e a `stream-failures` retêm as
mensagens por 14 dias. A `QueuePolicy` de cada fila de pedidos libera `sqs:SendMessage` para
`events.amazonaws.com` com o `aws:SourceArn` da própria regra.

- **Reenvio adiado:** sem quota, ou diante de uma entrega duplicada que pode estar viva, o worker reenvia o
  pedido com `DelaySeconds` (até 900 s) e preserva o atributo `dlqAttempt`, para não gastar
  `maxReceiveCount` ([`DelayedRedelivery`](../functions/src/main/java/com/mmda/serverless/provider/functions/DelayedRedelivery.java)).
- **Reprocessamento:** o `reconciler` lê até 10 mensagens por DLQ a cada ciclo. Abaixo de `dlqMaxAttempts`
  (3) e antes do `deadlineAt` do pedido, devolve a mensagem à fila de origem com `dlqAttempt + 1`; senão,
  ou com corpo ilegível, apaga e abre `DEAD_LETTER` ([ADR 0007](../../docs/decisoes/0007-dlq-e-reprocessamento.md),
  [`DeadLetterPolicy`](../domain/src/main/java/com/mmda/serverless/provider/domain/operations/DeadLetterPolicy.java)).
- **`stream-failures`:** a mensagem só aponta o shard e a faixa de sequência; serve ao alarme. O registro
  perdido volta pela republicação do outbox pendente.

A relação entre visibilidade, timeouts, lease e lápide é conferida pelo `ServerlessConfigurationTest`; as
invariantes estão no [§9](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo).

## Segurança e IAM

- **Callbacks:** a assinatura é conferida em tempo constante sobre os bytes recebidos
  ([`CallbackSignature`](../domain/src/main/java/com/mmda/serverless/provider/domain/insurer/CallbackSignature.java)),
  antes de qualquer leitura ou escrita. O log só leva o provedor do caminho quando ele é conhecido.
- **Simuladores:** a rota `ANY /sim/{proxy+}` não tem autorizador nem assinatura. Ela é o fornecedor
  simulado do laboratório e também fica aberta no stage `dev` da AWS.
- **Segredos:** `provider-solution/{stage}/query-key-secret` (chave HMAC da `queryKey`, lida só pelo
  `enrichmentWorker`) e `provider-solution/{stage}/callback-secret` (assinatura dos callbacks, lida só pela
  `api` e pelo `simCallbackSender`). Os dois são gerados pela stack, com 48 caracteres sem pontuação.
- **Dados nos eventos:** nenhum evento leva credencial, CPF ou resposta bruta; o `safeSummary` das
  ocorrências é um resumo em português, sem dado do risco.

| Papel | Funções | Permissões declaradas |
|---|---|---|
| Compartilhado (`provider.iam.role`, gerado pelo Serverless) | `enrichmentWorker`, `insurerWorker`, `reconcileWorker`, `outboxRelay`, `reconciler` | `GetItem`, `PutItem`, `UpdateItem`, `ConditionCheckItem` e `Query` na tabela de consultas e no `gsi2`; `s3:PutObject` em `raw/*`; `events:PutEvents` no barramento; leitura do `query-key-secret`; `sqs:SendMessage` nas 3 filas de pedidos, na `sim-callbacks` e na `stream-failures`; `sqs:ReceiveMessage` e `sqs:DeleteMessage` nas 4 DLQs |
| `CallbacksApiRole` | `api` | Logs do próprio grupo; `GetItem`, `PutItem`, `UpdateItem` e `Query` na tabela de consultas; `s3:PutObject` em `raw/*`; leitura do `callback-secret` |
| `SimulatorsRole` | `simulators` | Logs do próprio grupo; `GetItem`, `PutItem` e `UpdateItem` na tabela dos simuladores; `sqs:SendMessage` na `sim-callbacks` |
| `SimCallbackSenderRole` | `simCallbackSender` | Logs do próprio grupo; `ReceiveMessage`, `DeleteMessage` e `GetQueueAttributes` na `sim-callbacks`; leitura do `callback-secret` |

No papel compartilhado, o Serverless acrescenta as permissões de log e as de leitura das filas e do stream
que os gatilhos exigem. O Floci não aplica IAM: a conferência do IAM efetivo é o item V05 do
[roteiro de verificação na AWS](../../docs/implantacao-aws-dev.md#4-roteiro-de-verificação). O contexto
dos papéis está no [§2](../../docs/plataforma-de-cotacao.md#2-recursos-por-stack).

## Observabilidade e alarmes

- **Logs:** cada função escreve no grupo `/aws/lambda/provider-solution-{stage}-<função>`. O adaptador do
  Spring Cloud Function fica em `WARN`, porque registraria cada evento recebido, com dados do risco.
- **Métricas próprias:** linhas EMF na saída padrão, no namespace `mmda/provider-solution`
  ([`Metrics`](../functions/src/main/java/com/mmda/serverless/provider/functions/Metrics.java)).

| Métrica | Dimensões | Quem publica | Quando |
|---|---|---|---|
| `ProviderCalls` e `ProviderFailures` | `Stage`, `Provider` | `enrichmentWorker` e `insurerWorker` | A cada chamada; falha é a chamada sem resposta de negócio (transitória, sem resposta, outro 4xx ou ilegível) |
| `UnmatchedCallbacks` | `Stage` | `reconciler` | A cada ciclo: callbacks `UNMATCHED` ao fim dele |
| `UnknownQueries` | `Stage` | `reconciler` | A cada ciclo: consultas conferidas que continuam `UNKNOWN` |
| `OutboxPendingAgeSeconds` | `Stage` | `reconciler` | A cada ciclo: idade, pelo `createdAt`, do outbox pendente mais antigo |

São 16 alarmes, com nome `provider-solution-{stage}-<sufixo>` e `AlarmActions` no tópico
`provider-solution-{stage}-alarms` (`AlarmsTopic`). A assinatura por e-mail (`AlarmsEmailSubscription`) só
existe com o param `alarmEmail` preenchido (condição `HasAlarmEmail`) e precisa ser confirmada.

| Sufixo | Métrica | Limiar (param) |
|---|---|---|
| `<fila>-dlq-age`, para as 4 DLQs | `ApproximateAgeOfOldestMessage`, máximo em 60 s | `dlqAlarmAgeSeconds` (300) |
| `<fila>-dlq-volume`, para as 4 DLQs | `ApproximateNumberOfMessagesVisible`, máximo em 60 s | `dlqAlarmVisibleMessages` (1) |
| `stream-failures-age` e `stream-failures-volume` | As mesmas, na `stream-failures` | Os mesmos |
| `outbox-pending-age` | `OutboxPendingAgeSeconds` | `staleOutboxAfterSeconds` (120) |
| `unmatched-callbacks` | `UnmatchedCallbacks` | `unmatchedCallbacksAlarmThreshold` (1) |
| `unknown-queries` | `UnknownQueries` | `unknownQueriesAlarmThreshold` (1) |
| `enrichment-sim-provider-failures`, `alpha-provider-failures`, `beta-provider-failures` | `IF(calls > 0, 100 * failures / calls, 0)` sobre `ProviderFailures` e `ProviderCalls` | `providerFailureAlarmPercent` (50) na janela de `providerFailureAlarmPeriodSeconds` (300) |

No Floci, os alarmes são criados e aparecem no `DescribeAlarms`, mas não disparam
([§13](../../docs/plataforma-de-cotacao.md#13-limites-do-floci-240)). O disparo, a notificação e a extração
do EMF ficam para os itens V15 e V16 do
[roteiro na AWS](../../docs/implantacao-aws-dev.md#4-roteiro-de-verificação).

## Configuração

Os valores ficam no bloco `params` do [`serverless.yml`](../serverless.yml): o `default` traz os da AWS, e
o `local`, só o que difere no Floci. A referência de cada parâmetro, com valores por stage e efeito, é o
[§12](../../docs/plataforma-de-cotacao.md#12-parâmetros-por-stage); aqui fica só o agrupamento.

| Grupo | Parâmetros | Muda no `local` |
|---|---|---|
| Endpoints | `awsEndpointUrl`, `s3PathStyleAccess`, `providersBaseUrl` | Sim: `http://floci:4566`, path-style e a URL `_user_request_` do Floci |
| Chamadas e retries | `providerHttpTimeoutSeconds`, `workerRetries`, `retryBackoffMaxSeconds`, `workerTimeoutSeconds` | Não |
| Reuso | `enrichmentLeaseSeconds`, `inflightTombstoneSeconds`, `reusePolicyVersion`, `enrichmentValiditySeconds` | Não |
| Quota | `enrichmentSimQuotaPerMinute`, `alphaQuotaPerMinute`, `betaQuotaPerMinute` | Não |
| Simuladores | `offerValiditySeconds`, `betaCallbackDelaySeconds`, `betaLateCallbackDelaySeconds`, `simCallbackSenderTimeoutSeconds`, `simCallbacksVisibilityTimeoutSeconds`, `simCallbacksMaxReceiveCount` | Sim: `betaLateCallbackDelaySeconds` é 30 (120 na AWS) |
| Reconciliação | `outboxRepublishAfterSeconds`, `dlqMaxAttempts`, `unmatchedCallbackAfterSeconds`, `inboxStuckAfterSeconds`, `staleOutboxAfterSeconds`, `statusReadBudgetSeconds`, `reconcilerRate` | Não |
| Filas de pedidos | `requestQueueVisibilityTimeoutSeconds`, `requestQueueMaxReceiveCount` | Não |
| Alarmes | `dlqAlarmVisibleMessages`, `dlqAlarmAgeSeconds`, `unmatchedCallbacksAlarmThreshold`, `unknownQueriesAlarmThreshold`, `providerFailureAlarmPercent`, `providerFailureAlarmPeriodSeconds`, `alarmEmail` | Não |

Com `awsEndpointUrl` vazio, a condição `HasAwsEndpointUrl` tira das funções o `AWS_ENDPOINT_URL` e o
`AWS_ENDPOINT_URL_S3` ([ADR 0008](../../docs/decisoes/0008-aws-endpoint-url-s3-no-offline.md)). Alguns
valores ficam fixos no `application.yml` de cada módulo, e não em params:

| Propriedade | Valor | Módulo |
|---|---|---|
| `provider.event-source` | `mmda.provider` | `provider-functions` |
| `provider.supported-insurers` | `alpha,beta` | `provider-functions` |
| `provider.operations.status-insurers` | `beta` (seguradoras com API de situação) | `provider-functions` |
| `provider.credential-ref` | `${PROVIDER_CREDENTIAL_REF:default}`; o `serverless.yml` não define a variável | `provider-functions` |
| `callbacks.providers` | `beta` (provedores que enviam callbacks) | `provider-api` |

## Rodar localmente no Floci

1. Na raiz, suba o Floci e gere os pacotes, como na [primeira execução](../../README.md#primeira-execução):
   `docker compose up -d` e `mvn -B -q package -DskipTests`.
2. Implante o barramento e depois a provider. A ordem e os scripts estão em
   [Implantação no Floci](../../README.md#implantação-no-floci):

   ```bash
   pnpm deploy:platform
   pnpm deploy:provider
   ```

3. Para a plataforma de cotação inteira, implante também a quote (`pnpm deploy:quote`) ou use
   `pnpm deploy:local`, que implanta todas as stacks.
4. Acompanhe tabelas, filas, funções e logs na console do Floci (porta 4500) ou com o perfil `localstack`
   do AWS CLI ([Perfil do AWS CLI](../../README.md#perfil-do-aws-cli)).

No stage `local`, as funções chamam os simuladores e a `api` pelo Floci, em
`http://floci:4566/restapis/<api>/local/_user_request_`, e o callback do `vehicle-beta-late` sai em 30 s,
para passar da espera de 20 s da seguradora na quote, como o cenário exige. Os testes de integração não usam esse Floci: cada módulo sobe o próprio
por Testcontainers ([Integração com Floci](../../docs/testes.md#integração-com-floci)). Os limites do
emulador que afetam a provider estão no [§13](../../docs/plataforma-de-cotacao.md#13-limites-do-floci-240).

A remoção da stack não tem script. Como o CloudFormation não apaga bucket com objetos, esvazie o de
payloads antes (sequência não exercitada nesta documentação):

```bash
aws --profile localstack s3 rm s3://provider-solution-local-payloads --recursive
(cd provider-solution && pnpm sls remove --stage local)
```

## Implantar na AWS

O stage `dev` usa os valores do `params.default`. A implantação da plataforma de cotação, com os
pré-requisitos (nome global do bucket de payloads, e-mail dos alarmes, segredos), os comandos e a limpeza,
está no [guia de implantação na AWS](../../docs/implantacao-aws-dev.md): veja as seções
[3. Implantação](../../docs/implantacao-aws-dev.md#3-implantação) e
[7. Limpeza do laboratório](../../docs/implantacao-aws-dev.md#7-limpeza-do-laboratório). O que o Floci não
reproduz e precisa ser conferido na AWS para a provider (callbacks com corpo fora do ASCII, IAM efetivo,
`JOIN` ponta a ponta, retry do stream, tempos reais de DLQ, alarmes e EMF) está no
[roteiro de verificação](../../docs/implantacao-aws-dev.md#4-roteiro-de-verificação).

## Testes

Os níveis, os comandos (inclusive a seleção dos módulos da provider no Maven) e o que cada classe cobre
estão em [Testes](../../docs/testes.md). Em resumo, para a provider:

| Nível | O que existe na provider | Onde está o detalhe |
|---|---|---|
| Unitário e contrato | Domínio sem AWS, simuladores, configuração do `serverless.yml` e contratos OpenAPI e AsyncAPI | [Unitários](../../docs/testes.md#unitários) e [Contrato](../../docs/testes.md#contrato) |
| Integração com Floci | `*IT` do `provider-api`, do `provider-functions` e do `provider-simulators`, com um Floci por módulo | [Integração com Floci](../../docs/testes.md#integração-com-floci) |
| Ponta a ponta | Sem módulo próprio: o `quote-e2e` exercita a provider implantada | [Ponta a ponta](../../docs/testes.md#ponta-a-ponta) |

Os casos de teste por marco estão no [§14](../../docs/plataforma-de-cotacao.md#14-testes-por-marco).

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas), todos com o tema único
[`tema-plataforma.iuml`](../../docs/diagramas/tema-plataforma.iuml), incluído na linha 2 por
`!include ../../../docs/diagramas/tema-plataforma.iuml`. Os C4 usam a biblioteca padrão embutida no
PlantUML (`<C4/C4_Container>`, `<C4/C4_Component>`, `<C4/C4_Deployment>`). Os renderizados não são
versionados. Para renderizar só os desta solução, na raiz do projeto:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/provider-solution 'provider-solution/docs/diagramas/*.puml'
```

| Diagrama | O que mostra e como ler |
|---|---|
| [`c4-conteiner`](diagramas/c4-conteiner.puml) | As 8 Lambdas, o API Gateway, as tabelas, o bucket, as filas, os segredos e os alarmes da stack, com a quote e o barramento por fora. Setas tracejadas roxas são assíncronas (EventBridge, SQS, stream) |
| [`c4-componente-api`](diagramas/c4-componente-api.puml) | Componentes da Lambda `api`, do `StreamLambdaHandler` à transação de conclusão. Laranja é entrada HTTP, roxo é adaptador AWS, verde é regra da `provider-domain` |
| [`c4-componente-workers`](diagramas/c4-componente-workers.puml) | Componentes do `enrichmentWorker`, do `insurerWorker` e do `reconcileWorker`: handlers, registros na tabela, cliente HTTP, reenvio adiado e métricas |
| [`c4-componente-reconciler-e-relay`](diagramas/c4-componente-reconciler-e-relay.puml) | Os 6 deveres do `reconciler`, numerados na ordem em que rodam, e a `outboxRelay` com o publicador que os dois usam |
| [`c4-componente-simuladores`](diagramas/c4-componente-simuladores.puml) | Os três controllers da Lambda `simulators`, o estado deles na tabela dos simuladores e o `simCallbackSender` sem Spring |
| [`sequencia-enriquecimento`](diagramas/sequencia-enriquecimento.puml) | `EnrichmentRequested` até o `EnrichmentResolved`: reentrega, prazo, `HIT`, `JOIN` e liderança com chamada, conclusão e fan-out. Vermelho é conclusão sem chamada ou quota esgotada |
| [`sequencia-cotacao-seguradora`](diagramas/sequencia-cotacao-seguradora.puml) | `InsurerQuoteRequested` na alpha (síncrona) e na beta (`202` e metade do worker no `CORR#`), com retries, quota e o caso sem resposta |
| [`sequencia-callback`](diagramas/sequencia-callback.puml) | Do agendamento na beta ao `200 PROCESSED`: validação na ordem do controller, inbox, correlação pelo `clientReference` ou pelo `CORR#` e a transação de conclusão |
| [`sequencia-publicacao-outbox`](diagramas/sequencia-publicacao-outbox.puml) | `OUTBOX#` até o barramento pela `outboxRelay`, com falha transitória, permanente, `stream-failures` e a republicação do `reconciler` |
| [`sequencia-reconciliacao`](diagramas/sequencia-reconciliacao.puml) | Um ciclo do `reconciler`: cada `group` é um dever isolado, na ordem do código, e no fim vêm as métricas EMF |
| [`sequencia-reprocessamento-dlq`](diagramas/sequencia-reprocessamento-dlq.puml) | Mensagem que falha até a DLQ e a decisão do `DeadLetterPolicy`: reenvio com `dlqAttempt + 1` (verde) ou descarte com `DEAD_LETTER` (vermelho) |
| [`sequencia-reconciliacao-manual`](diagramas/sequencia-reconciliacao-manual.puml) | Pedido do operador na quote até o `QueryReconciled`: `OPERATION#` `RUNNING`, dono da consulta, reconciliação e resultado |
| [`estados-consulta`](diagramas/estados-consulta.puml) | `QueryState` da `QUERY#`: `IN_FLIGHT`, `COMPLETED`, `FAILED` e `UNKNOWN`, com o índice de cada estado. Seta vermelha leva a `UNKNOWN`, verde sai dele |
| [`estados-lideranca`](diagramas/estados-lideranca.puml) | `INFLIGHT#` da `queryKey`: lease válida (aceita `JOIN`), lease vencida (pode ser assumida), lápide `COMPLETED` e expiração pelo TTL; mostra quando a cadeia continua e quando começa outra |
| [`estados-inbox-callback`](diagramas/estados-inbox-callback.puml) | `processingStatus` do `INBOX#`: `RECEIVED`, `PROCESSED` e `UNMATCHED`, com quem faz cada transição |
| [`estados-ocorrencia`](diagramas/estados-ocorrencia.puml) | `ISSUE#`: `OPEN` e `RESOLVED`, a reabertura, a recorrência do `DEAD_LETTER` e a tabela de tipo, alvo e condição de resolução |
| [`estados-operacao`](diagramas/estados-operacao.puml) | `OPERATION#` da reconciliação manual: `RUNNING` (revisão 1) até `SUCCEEDED` ou `FAILED` (revisão 2), inclusive pelo descarte da DLQ |
| [`estados-outbox`](diagramas/estados-outbox.puml) | Estados implícitos do `OUTBOX#` (sem enum): pendente, pendente com falha permanente e publicado, com o `STALE_OUTBOX` pela idade do `createdAt` |
| [`estados-mensagem-dlq`](diagramas/estados-mensagem-dlq.puml) | Ciclo de vida de uma mensagem de fila de trabalho pelo atributo `dlqAttempt`, até ser consumida ou descartada |
| [`estados-situacao-beta`](diagramas/estados-situacao-beta.puml) | Situação da beta simulada, calculada na leitura pelo `readyAt`: `PROCESSING`, `COMPLETED` e 404 depois do TTL |
| [`modelo-dynamodb`](diagramas/modelo-dynamodb.puml) | As duas tabelas, cada tipo de item com `pk`, `sk`, atributos e o `gsi2` que usa. Linha cheia é a mesma partição; tracejada, referência por atributo |
| [`implantacao-floci`](diagramas/implantacao-floci.puml) | Stage `local`: contêiner do Floci com as stacks, contêineres das Lambdas criados pelo Floci e a console. Vermelho é o que o Floci cria sem efeito (alarmes) |
| [`implantacao-aws`](diagramas/implantacao-aws.puml) | Stage `dev` na conta AWS: stack, bucket de deploy, barramento da plataforma, papéis, alarmes com o tópico e a assinatura condicional |
| [`classes-dominio-base`](diagramas/classes-dominio-base.puml) | Pacote raiz da `provider-domain`: `QueryState`, `CallOutcome`, identificadores, `eventId` determinísticos, retry e janela de quota |
| [`classes-dominio-enriquecimento`](diagramas/classes-dominio-enriquecimento.puml) | Pacote `enrichment`: pedido, resposta, veredito, `ReusePlan` e suas decisões, `Leadership`, `ReuseIndexEntry` e o `EnrichmentResolved` |
| [`classes-dominio-seguradora`](diagramas/classes-dominio-seguradora.puml) | Pacote `insurer`: veredito da seguradora, oferta, `InsurerResultRecorded`, callback, assinatura, `Correlation` e a situação da API de consulta |
| [`classes-dominio-operacoes`](diagramas/classes-dominio-operacoes.puml) | Pacote `operations`: tipos e transições de ocorrência, eventos operacionais e a política das DLQs |
| [`classes-funcoes-registros`](diagramas/classes-funcoes-registros.puml) | Registros do `provider-functions` sobre a tabela de consultas, com os enums de resultado de cada gravação condicional |
| [`casos-de-uso`](diagramas/casos-de-uso.puml) | Quem aciona a provider (quote, seguradora, agendador) e o que ela faz; `<<extend>>` são os três caminhos do enriquecimento, `<<include>>` o que sempre acontece |

## Divergências conhecidas

1. **Atributos da consulta (§5).** A linha `QUERY` da tabela do
   [§5](../../docs/plataforma-de-cotacao.md#5-tabela-da-provider-solution) não cita o atributo
   `providerOperation` nem o `gsi2sk` do `QUERY_IN_FLIGHT`. O código grava os dois
   ([`QueryLedger`](../functions/src/main/java/com/mmda/serverless/provider/functions/QueryLedger.java),
   linhas 478 e 497), e o `reconciler` usa o `gsi2sk = deadlineAt` para achar as consultas vencidas.
2. **Fan-out pendente além da lápide (§9).** O §9 diz que o `reconciler` refaz o fan-out do
   `FANOUT_PENDING`. O [`FanOutRecovery`](../functions/src/main/java/com/mmda/serverless/provider/functions/FanOutRecovery.java)
   também descarta, com log de erro, o item concluído há mais que `inflightTombstoneSeconds`: marca o
   `fannedOutAt` sem responder aos seguidores (linhas 41 a 44).
3. **Lote das DLQs (§9).** O desenho não fixa quantas mensagens o `reconciler` lê por DLQ. O código faz um
   `ReceiveMessage` de até 10 por DLQ a cada ciclo
   ([`DeadLetterReprocessor`](../functions/src/main/java/com/mmda/serverless/provider/functions/DeadLetterReprocessor.java),
   linhas 32 e 84); um acúmulo maior é drenado ao longo de vários ciclos.
4. **Credencial da quota (§5 e §9).** O `QUOTA#` leva `<credentialRef>`, como se a quota fosse por
   credencial, mas a variável `PROVIDER_CREDENTIAL_REF` não existe no `serverless.yml`; o valor é sempre
   `default` (`application.yml` do `provider-functions`, linha 29).
5. **Perfil `e2e` vazio.** O [`pom.xml`](../pom.xml) da solução declara o perfil `e2e` sem módulos, com um
   comentário (linha 57) que fala do e2e exigir as stacks implantadas. A provider não tem módulo `e2e`, e
   o script `test:e2e` do `package.json` não a inclui.
