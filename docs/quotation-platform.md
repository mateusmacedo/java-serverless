# Plataforma de cotação: desenho técnico

Este documento detalha como o [PRD de backend](prds/PRD_Cotacao_Seguros_Backend_v2.md) é implementado
no monorepo. As decisões estão nos ADRs [0013](decisions/0013-barramento-da-plataforma.md),
[0014](decisions/0014-cotacao-em-duas-solucoes-e-task-token.md) e
[0015](decisions/0015-modelo-dynamodb-e-outbox-da-cotacao.md). O contrato da API de produto é o
`contracts/openapi.yaml`; os eventos estão em `docs/events/quote-solution.asyncapi.yaml` e
`docs/events/provider-solution.asyncapi.yaml`.

## 1. Módulos

```text
platform/events/              serverless.yml do barramento platform-<stage>-events
quote-solution/               parent quarkus-parent
  domain/   quote-domain      regras, estados, consolidação, idempotência, rótulos de missingFields (Java puro, sem SDK AWS)
  api/      quote-api         API de produto (quarkus-amazon-lambda-rest + smallrye-jwt); catálogo no classpath
  functions/quote-functions   workflow-steps, dispatch, resume, outbox-relay, issue-projector, reconciler
  e2e/      quote-e2e         cenários ponta a ponta no Floci (perfil e2e)
  serverless.yml
provider-solution/            parent spring-parent
  domain/   provider-domain   chave de reuso, validade, liderança, quota, classificação de respostas, contratos de provedor
  api/      provider-api      POST /v1/providers/{provider}/callbacks (Spring MVC + serverless-java-container)
  functions/provider-functions workers, outboxRelay, reconciler (Spring Cloud Function + aws-lambda-java-events)
  simulators/provider-simulators seguradora síncrona, seguradora assíncrona, provedor de enriquecimento
  e2e/      provider-e2e      testes ponta a ponta da provider isolada (perfil e2e)
  serverless.yml
contracts/openapi.yaml        API de produto; contracts/fixtures/*.json (o catálogo também vai para o classpath do quote-api)
```

Pacotes Java: `com.mmda.serverless.quote.{domain,api,functions}` e
`com.mmda.serverless.provider.{domain,api,functions,simulators}`. Os `artifactId` levam o prefixo da
solução, como na receipt.

## 2. Recursos por stack

| Stack | Recurso | Nome |
|---|---|---|
| platform | Barramento | `platform-<stage>-events` |
| quote | Tabela | `quote-solution-<stage>-quotes` (stream `NEW_IMAGE`, índices `gsi1` e `gsi2`) |
| quote | Fila de resultados + DLQ | `quote-solution-<stage>-results`, `quote-solution-<stage>-results-dlq` |
| quote | Fila de ocorrências + DLQ | `quote-solution-<stage>-issues`, `...-issues-dlq` (M5) |
| quote | Fila de falhas do stream | `quote-solution-<stage>-stream-failures` (destino `onFailure` da relay) |
| quote | Máquina de estado | `quote-solution-<stage>-quote-flow` |
| quote | Segredo da cifra de tokens | `quote-solution/<stage>/token-key` |
| quote | User pool, client e grupos Cognito | `quote-solution-<stage>-users` |
| quote | Alarmes CloudWatch | `quote-solution-<stage>-<alarme>` (M4, §9) |
| provider | Tabela | `provider-solution-<stage>-queries` (stream `NEW_IMAGE`, índice `gsi2`) |
| provider | Tabela dos simuladores | `provider-solution-<stage>-simulators` (TTL; acesso só da Lambda `simulators`) |
| provider | Bucket de respostas brutas | `provider-solution-<stage>-payloads` |
| provider | Filas + DLQs | `provider-solution-<stage>-enrichment-requests`, `...-insurer-requests`, `...-reconcile-requests` (M4) |
| provider | Fila de callbacks simulados (atraso) + DLQ | `provider-solution-<stage>-sim-callbacks`, `...-sim-callbacks-dlq` |
| provider | Fila de falhas do stream | `provider-solution-<stage>-stream-failures` (destino `onFailure` da relay) |
| provider | Segredo de assinatura de callbacks | `provider-solution/<stage>/callback-secret` |
| provider | Segredo da chave de reuso | `provider-solution/<stage>/query-key-secret` |
| provider | Alarmes CloudWatch | `provider-solution-<stage>-<alarme>` (M4, §9) |

Regras no barramento da plataforma, declaradas por quem consome:

| Regra (stack) | Padrão | Alvo |
|---|---|---|
| quote | `source: mmda.provider`, `detail-type: [EnrichmentResolved, InsurerResultRecorded]` | fila `results` da quote |
| quote | `source: mmda.provider`, `detail-type: [QueryReconciled, OperationIssueRaised, OperationIssueUpdated]` | fila `issues` da quote (M5) |
| provider | `source: mmda.quote`, `detail-type: [EnrichmentRequested]` | fila `enrichment-requests` |
| provider | `source: mmda.quote`, `detail-type: [InsurerQuoteRequested]` | fila `insurer-requests` |
| provider | `source: mmda.quote`, `detail-type: [QueryReconcileRequested]` | fila `reconcile-requests` (M4) |

Cada fila alvo tem `QueuePolicy` que libera `sqs:SendMessage` para `events.amazonaws.com` com
`aws:SourceArn` da regra, como a `AuditQueuePolicy` da sample. Lambdas consomem filas com
`batchSize: 1`. Isso vale só para filas: o stream da tabela usa lotes de 100 (§7). Toda fila tem
`VisibilityTimeout` explícito e `maxReceiveCount` próprio, com os valores do §12.

## 3. Eventos

Todo `detail` tem `eventId` (UUID), `occurredAt` (ISO-8601 UTC) e `schemaVersion` (`"1"`). Valores
monetários são strings decimais; datas com hora em UTC.

| detail-type | source | Campos do `detail` além do envelope |
|---|---|---|
| `QuoteAccepted` | mmda.quote | `quoteId`, `version`, `organizationId` |
| `EnrichmentRequested` | mmda.quote | `workRef`, `organizationId`, `quoteId`, `version`, `provider` (`enrichment-sim`), `operation` (`VEHICLE_DRIVER_PROFILE`), `contractVersion` (`1`), `params` (`vehicleRef`, `principalDriverRef`), `deadlineAt` |
| `InsurerQuoteRequested` | mmda.quote | `workRef`, `organizationId`, `quoteId`, `version`, `insurerId`, `risk` (cópia autorizada do snapshot: `product`, `usage`, `vehicle`, `principalDriver`, `coverageProfile`, `desiredStartDate`), `enrichment` (resumo), `deadlineAt` |
| `QuoteCompleted` | mmda.quote | `quoteId`, `version`, `outcome`, `outcomeReason`, `offerCount` |
| `OfferSelected` | mmda.quote | `quoteId`, `version`, `offerId`, `selectionId` |
| `QueryReconcileRequested` | mmda.quote | `operationId`, `organizationId`, `queryId`, `reason`, `requestedBy` (consumido pela provider no M4; publicado pela API da quote a partir do M5) |
| `EnrichmentResolved` | mmda.provider | `workRef`, `quoteId`, `version`, `queryId`, `evidenceId`, `reuseDecision` (`HIT`, `JOIN`, `MISS`), `resultType` (`FOUND`, `NOT_FOUND`, `FAILED`), `result` (perfil normalizado, quando `FOUND`), `notFoundRefs` (quando `NOT_FOUND`: referências que o provedor declarou não encontrar, como `["vehicleRef"]`), `reusableUntil`, `errorClass` (quando `FAILED`) |
| `InsurerResultRecorded` | mmda.provider | `workRef`, `quoteId`, `version`, `insurerId`, `queryId`, `attemptRef`, `status` (`QUOTED`, `DECLINED`, `NEEDS_INFORMATION`, `UNAVAILABLE`), `reasonCode`, `missingFields` (lista de `{path, reasonCode}`, com `path` relativo ao snapshot, como `risk.principalDriver.licenseDate`), `offer` (quando `QUOTED`: `externalOfferId`, `insurerName`, `premiumTotal`, `currency`, `installments`, `coverages`, `deductibles`, `validUntil`, `rawResponseRef`) |
| `QueryReconciled` | mmda.provider | `operationId`, `organizationId`, `queryId`, `status` (`SUCCEEDED`, `FAILED`), `safeSummary`, `revision` (M4) |
| `OperationIssueRaised`, `OperationIssueUpdated` | mmda.provider | `issueId`, `organizationId` (ausente em callback sem correlação), `type` (`UNKNOWN_QUERY`, `UNMATCHED_CALLBACK`, `DEAD_LETTER`, `STALE_OUTBOX`), `status` (`OPEN`, `RESOLVED`), `queryId`, `quoteId`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision` (M4) |

Nenhum evento leva token, credencial, CPF ou resposta bruta. O `workRef` é
`wr_` + os 32 primeiros caracteres hexadecimais do SHA-256 de `quoteId|version|kind|insurerId`
(`kind` = `ENRICHMENT` ou `INSURER`; `insurerId` vazio no enriquecimento). O `deadlineAt` de um
pedido é o instante da `dispatch` mais o `TimeoutSeconds` da etapa, limitado ao `deadlineAt` da
versão.

O `eventId` é determinístico quando o mesmo fato pode ser gravado mais de uma vez. O outbox é gravado
com `attribute_not_exists`, então a repetição não cria um segundo evento:

- `EnrichmentRequested` e `InsurerQuoteRequested`: UUIDv5 do `workRef`.
- `EnrichmentResolved`: UUIDv5 de `queryId|workRef`, para o líder, cada seguidor e o `HIT`.
- `InsurerResultRecorded`: UUIDv5 do `queryId`.
- `QuoteCompleted`: UUIDv5 de `quoteId|version|COMPLETED`; `OfferSelected`: UUIDv5 do `selectionId`.
- `OperationIssueRaised`, `OperationIssueUpdated` e `QueryReconciled`: UUIDv5 de `issueId|revision`
  ou `operationId|revision`.
- Os demais usam UUID aleatório, gravado uma vez na transação que cria o fato.

Consumidores de eventos de resultado (`resume` e `issue-projector`) descartam repetições pelo
`eventId`: gravam o `CONSUMED#<eventId>` na mesma transação do efeito, nunca antes. Pedidos
(`EnrichmentRequested`, `InsurerQuoteRequested` e `QueryReconcileRequested`) são deduplicados pela
identidade do pedido lógico (`workRef`, `queryId` ou `operationId`). A reentrega de um pedido relê a
consulta e decide pelo estado dela (§8). O `CONSUMED` que a provider grava junto com o efeito de um
pedido usa o `eventId` do pedido, que é o UUIDv5 do `workRef`: ele registra a identidade do pedido e
não substitui a leitura da consulta.

O `revision` de `OperationIssueRaised`, `OperationIssueUpdated` e `QueryReconciled` é monotônico por
`issueId` e por `operationId` e é mantido no item de origem da provider (§5). O `issue-projector` grava
com a condição `attribute_not_exists(revision) OR revision < :r` e descarta versões antigas.

A quote também grava ocorrências próprias, direto no `ISSUE#` e sem evento, pelo `reconciler` (M4):
`WORKFLOW_FAILED` (execução `FAILED`, `TIMED_OUT` ou `ABORTED`), `TASK_EXPIRED` (waiter `EXPIRED` com
resultado não entregue), `STUCK_VERSION` (`ACTIVE_VERSION` além de `deadlineAt` +
`stuckVersionMarginSeconds`) e `DEAD_LETTER` (`results-dlq` e, a partir do M5, `issues-dlq`).

Os AsyncAPI das duas soluções definem esses schemas, inclusive o de `missingFields` com paths
relativos ao snapshot.

## 4. Tabela da quote-solution

| Item | `pk` | `sk` | Atributos principais |
|---|---|---|---|
| Cotação | `QUOTE#<quoteId>` | `META` | `organizationId`, `createdBy`, `currentVersion`, `createdAt`, `gsi1pk`, `gsi1sk`, resumo da versão atual (`phase`, `outcome`, `offerCount`), `selectionId`, `selectedVersion`, `selectedOfferId` |
| Versão | `QUOTE#<quoteId>` | `V#<nnnn>` | `snapshot` (JSON), `inputHash`, `phase`, `outcome`, `outcomeReason`, `missingFields`, `eligibilityRuleVersion`, `selectionRuleVersion`, `workflowVersion`, `executionName`, `executionArn`, `deadlineAt`, `supersededAt`, `revision`, `updatedAt`, `gsi2pk = ACTIVE_VERSION` enquanto não terminal |
| Resultado da seguradora | `QUOTE#<quoteId>` | `V#<nnnn>#RESULT#<insurerId>` | `insurerName`, `status` (`PENDING`, `PROCESSING` ou terminal), `reasonCode`, `missingFields`, `queryRef`, `attemptRef`, `revision`, `updatedAt` |
| Oferta | `QUOTE#<quoteId>` | `V#<nnnn>#OFFER#<offerId>` | `insurerId`, `insurerName`, `externalOfferId`, `premiumTotal`, `currency`, `installments`, `coverages`, `deductibles`, `validUntil`, `rawResponseRef`, `createdAt` |
| Seleção | `QUOTE#<quoteId>` | `V#<nnnn>#SELECTION` | `selectionId`, `offerId`, `selectedAt`, `selectedBy` |
| Evidência usada | `QUOTE#<quoteId>` | `V#<nnnn>#EVIDENCE#<workRef>` | `evidenceId`, `queryId`, `reuseDecision`, `resultType`, `policyVersion`, `summary` |
| Resultado tardio | `QUOTE#<quoteId>` | `V#<nnnn>#LATE#<alvo>#<queryId>` | `alvo` = `insurerId` ou `ENRICHMENT`; `reason` (`LATE`, `DUPLICATE`), `status`, `queryRef`, `attemptRef`, `rawResponseRef`, resumo da oferta, `receivedAt` |
| Idempotência | `IDEMPOTENCY#<orgId>#<operation>#<scope>#<key>` | `META` | `requestHash`, `resourceRef`, `createdAt` |
| Waiter | `WAITER#<workRef>` | `META` | `tokenCiphertext`, `quoteId`, `version`, `kind`, `insurerId`, `executionArn`, `expiresAt`, `status` (`WAITING`, `RESUMING`, `DONE`, `EXPIRED`), `resultEventId`, `resumePayload`, `updatedAt`, `gsi2pk = WAITER_RESUMING` quando `RESUMING` |
| Outbox | `OUTBOX#<eventId>` | `META` | `detailType`, `detail`, `createdAt`, `publishedAt`, `attempts`, `lastError`, `gsi2pk = OUTBOX_PENDING` até publicar |
| Evento consumido | `CONSUMED#<eventId>` | `META` | `consumedAt` (descarte de eventos de resultado repetidos) |
| Ocorrência operacional | `ISSUE#<issueId>` | `META` | `organizationId`, `source` (`quote`, `provider`), `type`, `status`, `queryId`, `quoteId`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision`, `gsi1pk = ISSUES#<organizationId>`, `gsi1sk = <lastSeenAt>#<issueId>` (M4) |
| Consulta conhecida | `QUERYREF#<queryId>` | `META` | `organizationId`; gravado pelo `issue-projector`, autoriza o `RECONCILE` (M5) |
| Reconciliação manual | `OPERATION#<operationId>` | `META` | `organizationId`, `queryId`, `status`, `safeSummary`, `revision`, `updatedAt` (M5) |

`<nnnn>` é a versão com quatro dígitos. Todo item tem o atributo `type` (ADR 0015), como `QUOTE`,
`VERSION` ou `OUTBOX`; o filtro do stream depende dele. `eligibilityRuleVersion` é gravado pelo passo
`evaluate-eligibility` e `selectionRuleVersion` pelo `select-insurers`, cada um quando aplica a regra.
O `issue-projector` grava o `ISSUE#` sem `gsi1pk` quando o evento não traz `organizationId`.

**Resumo do META.** A transação principal de cada passo grava só o `V#n` e os itens filhos. O resumo do
`META` (`phase`, `outcome`, `offerCount`) é atualizado depois, por um `UpdateItem` separado, com
valores absolutos: `SET`, nunca `ADD`; o `offerCount` sai de uma contagem com `ConsistentRead`. A
condição é `currentVersion = :v`, e um `ConditionalCheckFailed` é ignorado, porque indica versão
substituída. A criação de versão grava o resumo inicial de `v+1` na mesma transação que muda o
`currentVersion`. O `reconciler` repara um resumo que divirja do `V#currentVersion`.

**Revision.** O `revision` da versão é calculado na leitura, com `Query` e `ConsistentRead` nos itens
`V#<nnnn>*` da cotação, como soma de contadores monotônicos:

- `V#n.revision`, incrementado por toda escrita que muda campos expostos do `V#n`: os passos
  `load-snapshot`, `evaluate-eligibility`, `select-insurers` e `consolidate`, a substituição e a
  finalização pelo `reconciler`. A gravação do `executionArn` pela relay não conta, porque o campo não
  aparece na API;
- `RESULT.revision`, incrementado a cada mudança de status;
- o número de ofertas;
- 1 se houver seleção.

O `revision` da lista de ofertas é o número de ofertas mais os resultados terminais, mais 1 se houver
seleção e mais 1 se `supersededAt` existir. A expiração por relógio continua derivada de
`serverTime`.

**Idempotência.** `operation` é `CREATE_QUOTE`, `CREATE_VERSION`, `SELECT_OFFER` ou `RECONCILE`.
`scope` é `-` na criação, o `quoteId` na nova versão e na seleção, e o `queryId` na reconciliação. O
`requestHash` é o SHA-256 do body em JSON canônico (RFC 8785). O tratamento de conflitos está no §8.

**Lacuna de contrato: `outcomeReason`.** O T26 exige `outcomeReason`, mas o `GET
/v1/quotes/{id}/versions/{v}` do contrato compartilhado não o lista. Decisão: acrescentar
`outcomeReason` a esse GET, com enum fechado: `SALVAGE_VEHICLE`, `MISSING_LICENSE_DATE`,
`ENRICHMENT_NOT_FOUND`, `ENRICHMENT_FAILED`, `NO_APPLICABLE_INSURER`, `ALL_INSURERS_DECLINED`,
`INSURER_NEEDS_INFORMATION`, `INSURER_TECHNICAL_FAILURE` e `WORKFLOW_FAILED`; nulo em `COMPLETED` e
`PARTIAL`. É adição compatível; a seção compartilhada dos dois PRDs já lista o campo, então os dois
continuam idênticos e o T22 continua válido.

## 5. Tabela da provider-solution

| Item | `pk` | `sk` | Atributos principais |
|---|---|---|---|
| Consulta | `QUERY#<queryId>` | `META` | `queryKey` (só `ENRICH`), `provider`, `operation` (`ENRICH`, `QUOTE`), `contractVersion`, `organizationId`, `state` (`IN_FLIGHT`, `COMPLETED`, `FAILED`, `UNKNOWN`), `clientReference`, `providerRequestId`, `fencingVersion` (só `ENRICH`), `workRef`, `quoteId`, `version`, `insurerId`, `deadlineAt`, `gsi2pk = QUERY_IN_FLIGHT` em voo e `QUERY_UNKNOWN` quando `UNKNOWN` |
| Tentativa | `QUERY#<queryId>` | `ATTEMPT#<nnnn>` | `startedAt` (gravado antes do HTTP), `finishedAt`, `outcome`, `errorClass`, `providerRequestId` |
| Evidência | `QUERY#<queryId>` | `EVIDENCE#<evidenceId>` | `normalizedResult`, `rawResponseRef`, `observedAt`, `receivedAt`, `reusableUntil`, `resultType`, `checksum`, `fencingVersion` |
| Índice de reuso | `REUSE#<queryKey>` | `META` | `evidenceId`, `queryId`, `reusableUntil`, `resultType`, `revision` |
| Liderança | `INFLIGHT#<queryKey>` | `META` | `state` (`IN_FLIGHT`, `COMPLETED`), `fencingVersion`, `leaderWorkRef`, `queryId`, `leaseUntil`, `evidenceId`, `resultType`, `ttl` (lápide depois de `COMPLETED`) |
| Seguidor | `INFLIGHT#<queryKey>` | `FOLLOWER#<fencingVersion>#<workRef>` | `quoteId`, `version`, `registeredAt` |
| Correlação | `CORR#<provider>#<providerRequestId>` | `META` | `queryId` (gravado pelo worker depois do `202`), `pendingEvents` (conjunto de `externalEventId` recebidos antes) |
| Inbox | `INBOX#<provider>#<externalEventId>` | `META` | `payload`, `providerRequestId`, `clientReference`, `receivedAt`, `processingStatus` (`RECEIVED`, `PROCESSED`, `UNMATCHED`), `gsi2pk = INBOX_RECEIVED` enquanto `RECEIVED` e `INBOX_UNMATCHED` quando `UNMATCHED` |
| Quota | `QUOTA#<provider>#<credentialRef>#<janela>` | `META` | `used`, `ttl` |
| Ocorrência | `ISSUE#<issueId>` | `META` | `type`, `status`, `organizationId`, `queryId`, `quoteId`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision` (M4) |
| Reconciliação | `OPERATION#<operationId>` | `META` | `organizationId`, `queryId`, `status`, `safeSummary`, `revision` (M4) |
| Outbox | `OUTBOX#<eventId>` | `META` | igual à quote |
| Evento consumido | `CONSUMED#<eventId>` | `META` | `consumedAt` |

A `queryKey` é o HMAC-SHA256, com o segredo `query-key-secret` como chave, de
`organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef`. Pedidos de cotação
(`operation = QUOTE`) não usam reuso nem liderança.

O `queryId` é `q_` + os 32 primeiros caracteres hexadecimais do SHA-256 do `workRef` do pedido que cria
a consulta: cada pedido de seguradora e o líder do enriquecimento. A `QUERY` é criada com
`attribute_not_exists`, então a reentrega do mesmo pedido encontra a consulta existente. O `issueId` é
determinístico pelo tipo e pelo alvo (`queryId` ou `externalEventId`), e cada mudança da ocorrência
incrementa o `revision`. O limite de cada `QUOTA` vem da configuração por provedor (§12).

## 6. Máquina de estado principal

Entrada: `{"quoteId": "...", "version": 1}`. Nome da execução: `q-<quoteId>-v<version>`.
`TimeoutSeconds` da máquina: `quoteDeadlineSeconds` + `workflowMarginSeconds` (360 no local). Ele é
só rede de segurança: o prazo real vem da invariante de orçamento (§9). Um timeout da execução encerra
em `TIMED_OUT` sem passar por `Catch`, e o `reconciler` finaliza a versão (§9).

Os passos simples chamam a Lambda `workflow-steps` (`arn:aws:states:::lambda:invoke`) com
`Payload = {step, quoteId, version, ...}`. Cada passo é idempotente: escreve com condição e, numa
nova tentativa, relê e devolve o que já gravou. Depois da transação, o passo atualiza o resumo do
`META` pela regra do §4.

1. `LoadQuoteSnapshot` (`step = load-snapshot`): fase `VALIDATING`; devolve `organizationId` e
   `deadlineAt` em `$.context`.
2. `EvaluateEligibility` (`step = evaluate-eligibility`): `$.eligibility = {decision, reasonCode,
   missingFields}` e grava o `eligibilityRuleVersion`. Com `decision = ELIGIBLE`, grava a fase
   `ENRICHING`; com outra decisão, vai para `ConsolidateQuote`.
3. `ResolveEnrichment`: `lambda:invoke.waitForTaskToken` na `dispatch` com `kind = ENRICHMENT` e o
   `executionArn` (`$$.Execution.Id`); `TimeoutSeconds = enrichmentTimeoutSeconds`; resultado em
   `$.enrichment`. O `Catch` de `States.Timeout` e `States.ALL` chama `RecordEnrichmentOutcome`
   (`step = record-enrichment-outcome`): ele lê o waiter e usa o `resumePayload` quando existe; se não
   existe, devolve `{resultType: FAILED, errorClass}` e marca o waiter `EXPIRED` com a condição
   `status = WAITING`. Só `FOUND` segue; `NOT_FOUND` e `FAILED` vão para `ConsolidateQuote`.
4. `SelectInsurers` (`step = select-insurers`): `$.selection.insurers`, limitada a `maxInsurers`, e
   grava o `selectionRuleVersion`. Numa transação, grava a fase `QUOTING` e um `RESULT` `PENDING`, com
   `insurerName`, para cada seguradora. Lista vazia vai para `ConsolidateQuote`.
5. `QuoteInsurers`: `Map` com `MaxConcurrency = mapConcurrency` (2) e
   `ItemSelector = {quoteId, version, insurerId, executionArn}`. Cada item chama a `dispatch` com
   `kind = INSURER` e `TimeoutSeconds = insurerTimeoutSeconds`. `States.Timeout` chama
   `RecordInsurerOutcome` (`step = record-insurer-outcome`) com `TIMED_OUT`, e `States.ALL` com
   `UNAVAILABLE`; ele grava o `RESULT` só se ainda não for terminal e marca o waiter `EXPIRED` com a
   condição `status = WAITING`. `ResultPath: null`.
6. `ConsolidateQuote` (`step = consolidate`): aplica a precedência do PRD (§8) com atualização
   condicional pela `revision` do `V#n`, grava `TERMINAL`, `outcome`, `outcomeReason` e o outbox
   `QuoteCompleted`.

Toda tarefa Lambda da máquina (`workflow-steps` e `dispatch`) tem `Retry` para
`Lambda.ServiceException`, `Lambda.AWSLambdaException`, `Lambda.SdkClientException` e
`Lambda.TooManyRequestsException`, com backoff. `States.Timeout` não entra no `Retry`: a máquina não
repete esperas (§9).

Toda tarefa declara `TimeoutSeconds`: as esperas usam os parâmetros do §12, e os passos do
`workflow-steps`, o timeout da função. Assim o desenho não depende de nenhum valor padrão que o
`serverless-step-functions` injete (§13).

## 7. Funções

### quote-solution (`QUARKUS_LAMBDA_HANDLER`)

| Handler | Gatilho | Papel |
|---|---|---|
| API (`quote-api`) | API Gateway `/v1/{proxy+}` | API de produto |
| `workflow-steps` | Máquina (`lambda:invoke`) | Passos simples, escolhidos pelo campo `step`: `load-snapshot`, `evaluate-eligibility`, `select-insurers`, `consolidate`, `record-insurer-outcome`, `record-enrichment-outcome` |
| `dispatch` | Máquina (`waitForTaskToken`) | Waiter + outbox do pedido |
| `resume` | Fila `results` | Grava resultado, retoma com `SendTaskSuccess` |
| `outbox-relay` | Stream da tabela | Publica eventos, inicia execuções e grava o `executionArn` |
| `issue-projector` | Fila `issues` | Projeta ocorrências e reconciliações (M5) |
| `reconciler` | Agendamento (1 min) | M1: republica `OUTBOX_PENDING`. M4: waiters, versões presas, execuções encerradas, resumo do `META`, ocorrências da quote e DLQs (§9) |

No Floci, cada função Java vira um container, e a memória da máquina de desenvolvimento é limitada.
Por isso os passos simples ficam numa só função, `workflow-steps`. O papel IAM dela é a união dos
passos e só toca a tabela da quote. `dispatch`, `resume`, `outbox-relay`, `issue-projector` e
`reconciler` continuam separados, porque têm gatilho ou permissões próprios: `states:SendTaskSuccess`
só na `dispatch`, no `resume` e no `reconciler`; `states:StartExecution` na relay e no `reconciler`,
que republica também o `StartQuoteRequested` com o mesmo código de publicação da relay;
`states:DescribeExecution` só no `reconciler`.

### provider-solution (`SPRING_CLOUD_FUNCTION_DEFINITION`)

| Função | Gatilho | Papel |
|---|---|---|
| API (`provider-api`) | API Gateway `/v1/providers/{provider}/callbacks` | Inbox de callbacks |
| `simulators` (`provider-simulators`) | API Gateway `/sim/{proxy+}` | Fornecedores simulados |
| `simCallbackSender` | Fila `sim-callbacks` | Envia callbacks assinados à API |
| `enrichmentWorker` | Fila `enrichment-requests` | Reuso, liderança, quota, chamada ao provedor |
| `insurerWorker` | Fila `insurer-requests` | Quota, chamada à seguradora, correlação |
| `reconcileWorker` | Fila `reconcile-requests` | Reconciliação manual (M4) |
| `outboxRelay` | Stream da tabela | Publica eventos |
| `reconciler` | Agendamento (1 min) | M1: republica `OUTBOX_PENDING`. M4: leases vencidas, consultas `UNKNOWN`, inbox parada ou sem correlação, ocorrências e DLQs (§9) |

O `provider-functions` declara `com.amazonaws:aws-lambda-java-events` no escopo `compile`, ou recebe os
eventos como `Map`. O primeiro teste no Floci confere a desserialização de `SQSEvent` e
`DynamodbEvent`. A configuração leva `logging.level.org.springframework.cloud.function.adapter.aws=WARN`.

### Relay de outbox (as duas soluções)

O evento `stream` do `outbox-relay` e do `outboxRelay` declara:

- `batchSize: 100`;
- `filterPatterns` com `eventName: [INSERT]` e `dynamodb.NewImage.type.S: [OUTBOX]`;
- `maximumRetryAttempts: 3` e `bisectBatchOnFunctionError: true`;
- `functionResponseType: ReportBatchItemFailures`;
- `destinations.onFailure` para a fila `stream-failures`, que tem alarme.

A relay trata cada registro separadamente. Ela converte o `AttributeValue` do modelo de eventos para o
do SDK v2 ao ler o `NewImage`. `ExecutionAlreadyExists` com a mesma entrada conta como sucesso. O
`FailedEntryCount` do `PutEvents` é conferido por entrada. Erro transitório vira falha do item em
`batchItemFailures`. Erro permanente grava `attempts` e `lastError` no `OUTBOX` e não derruba o lote; o
item continua `OUTBOX_PENDING`.

Com `ReportBatchItemFailures`, o stream reentrega o lote a partir do registro que falhou, inclusive os
seguintes, e a divisão do lote reentrega registros vizinhos mais de uma vez (§13). Por isso a
publicação repetida não pode ter efeito: o `eventId` vai no `detail` e os consumidores descartam a
repetição (§3), `ExecutionAlreadyExists` conta como sucesso e o `publishedAt` é gravado com `SET`. A mensagem da `stream-failures` só aponta o shard e a faixa de
sequência, sem o registro: ela serve ao alarme. Um registro descartado do stream só volta pelo
republicador, que é o `reconciler` desde o M1 e lê o `OUTBOX_PENDING` na tabela, não no stream, que
guarda os registros por 24 h.

Na quote, depois do `StartExecution`, a relay grava o `executionArn` no `V#n`, inclusive quando o
`StartExecution` repetido devolve a execução existente. O ARN também é derivável do nome da máquina e
da execução.

As chamadas a fornecedores usam HTTP. A URL base vem de `PROVIDERS_BASE_URL`, montada no
`serverless.yml` com `Fn::Sub` a partir do `ApiGatewayRestApi` da própria stack: no stage `local`,
`http://floci:4566/restapis/${ApiGatewayRestApi}/local/_user_request_`; na AWS,
`https://${ApiGatewayRestApi}.execute-api.${AWS::Region}.amazonaws.com/<stage>`. O caminho
`_user_request_` chamado de dentro de uma Lambda ainda não foi verificado no Floci; a alternativa está
no §13.

## 8. Fluxos

**Criação.** O `POST /v1/quotes` resolve as referências no catálogo da organização e grava, numa
transação: idempotência, `META` (com o resumo inicial), `V#0001` (`phase = RECEIVED`), outbox interno
`StartQuoteRequested` e outbox `QuoteAccepted`. O `outbox-relay` inicia a execução com o nome
determinístico e grava o `executionArn` no `V#0001`; `ExecutionAlreadyExists` com a mesma entrada
conta como sucesso.

**Conflitos de idempotência.** O `TransactionCanceledException` é tratado pelo motivo de cada item:

- `ConditionalCheckFailed` no `IDEMPOTENCY`: ler o registro existente com `GetItem` e `ConsistentRead`
  (ou `ReturnValuesOnConditionCheckFailure = ALL_OLD`, se o Floci suportar; §13) e comparar o
  `requestHash`. Mesmo hash devolve a mesma identidade; hash diferente, 409 `IDEMPOTENCY_CONFLICT`.
- `TransactionConflict`: repetir com backoff e jitter e depois fazer `GetItem` com `ConsistentRead`. A
  API responde 503 só se o registro continuar ausente.

O mesmo tratamento vale para `CREATE_VERSION`, `SELECT_OFFER` e `RECONCILE`.

**Nova versão** (`POST /v1/quotes/{id}/versions`). Cotação de outra organização responde 404 antes da
transação. Uma `TransactWriteItems` grava:

1. Put do `IDEMPOTENCY` (`attribute_not_exists`).
2. Update do `META`: `SET currentVersion = :n` e o resumo inicial (`phase = RECEIVED`, `outcome`
   nulo, `offerCount = 0`), com a condição
   `currentVersion = :expected AND attribute_not_exists(selectionId) AND organizationId = :org`.
3. Update do `V#<expected>`: `SET supersededAt` e incremento do `revision`.
4. Put do `V#<n>` (`attribute_not_exists`), com `n = expected + 1`.
5. Outbox `StartQuoteRequested` e `QuoteAccepted` da nova versão.

Se a condição do `META` falhar, a API relê o `META`. Com `selectionId` presente, responde 409
`QUOTE_ALREADY_SELECTED`; sem ele, 409 `VERSION_CONFLICT`. A execução da versão antiga segue até o
fim, sem `StopExecution`.

**Seleção** (`POST /v1/quotes/{id}/versions/{v}/selection`). Uma `TransactWriteItems` grava:

1. Put do `IDEMPOTENCY`.
2. Put do `V#<v>#SELECTION` (`attribute_not_exists`).
3. Update do `META`: `SET selectionId, selectedVersion, selectedOfferId`, com a condição
   `currentVersion = :v AND attribute_not_exists(selectionId) AND organizationId = :org`.
4. `ConditionCheck` na `OFFER`: `attribute_exists(pk) AND validUntil > :agora`.
5. Outbox `OfferSelected`.

| Situação | Resposta |
|---|---|
| Cotação de outra organização | 404 |
| Seleção existente com a mesma `offerId` | Devolve a seleção existente, mesmo com chave nova (T28) |
| Seleção existente com outra `offerId` | 409 `SELECTION_ALREADY_EXISTS` |
| `v` diferente de `currentVersion` | 409 `VERSION_SUPERSEDED` |
| Falha na `OFFER` | 409 `OFFER_EXPIRED` |

Seleção e nova versão escrevem o mesmo `META`. Por isso o DynamoDB deixa só uma das duas passar
(T29).

**Ações permitidas.** O GET deriva os campos abaixo; `serverTime` é o relógio do servidor na leitura.

| Campo | Regra |
|---|---|
| `CREATE_VERSION` | `v = currentVersion`, sem `selectionId` no `META` e permissão `quotes:write` |
| `SELECT_OFFER` | `v = currentVersion`, sem `selectionId`, permissão `offers:select` e ao menos uma oferta `selectable` |
| `selectable` (oferta) | `v = currentVersion`, sem `selectionId` e `validUntil > serverTime` |
| `unavailabilityReason` (oferta) | `VERSION_SUPERSEDED`, `SELECTION_ALREADY_EXISTS` ou `OFFER_EXPIRED`, nessa ordem de precedência; nulo quando `selectable` |
| `RECONCILE` (ocorrência) | `queryId` presente, `reconcilable = true` e permissão `operations:reconcile` |

`unavailabilityReason` é um enum fechado no OpenAPI.

**Pedido.** A `dispatch` recebe `kind`, `insurerId` e o `executionArn`. Ela cifra o token
(AES-256-GCM com chave derivada do segredo `token-key`). O `eventId` do pedido é o UUIDv5 do
`workRef`, sem o token. A `dispatch` é idempotente:

- Na primeira vez, uma transação faz Put do waiter `WAITING` e do outbox do pedido, os dois com
  `attribute_not_exists`. Na seguradora, a mesma transação passa o `RESULT` de `PENDING` para
  `PROCESSING`, com escrita condicional.
- Nas seguintes (o `Retry` da tarefa gera token novo), só um Update do `tokenCiphertext`, com a
  condição `status = WAITING`, sem outbox novo.
- Se o waiter já tiver `resumePayload` (`RESUMING`, `DONE` ou `EXPIRED`), a `dispatch` chama
  `SendTaskSuccess` com o token novo e esse `resumePayload`.

A `dispatch` não grava o `V#n`: as fases são dos passos do `workflow-steps` (§6).

**Retomada.** O `resume` lê o evento da fila `results`. Se agora ≥ `expiresAt` do waiter (o
`deadlineAt` do pedido), grava `LATE` com o `CONSUMED` e para; como o prazo é imutável, a verificação
fica no código. Nos demais casos, faz uma única transação com:

- Update do waiter de `WAITING` para `RESUMING`, com `resultEventId` e `resumePayload` (condição
  `status = WAITING`);
- na seguradora, `RESULT` e `OFFER` (condição: resultado ainda não terminal); no enriquecimento, a
  evidência usada `V#n#EVIDENCE#<workRef>` com `attribute_not_exists`, que impede duas evidências para
  o mesmo pedido;
- o `CONSUMED#<eventId>`.

Se a condição falhar, o `resume` relê o waiter. `RESUMING` com o mesmo `resultEventId` segue para o
`SendTaskSuccess`: é a retomada refeita na reentrega. Fora desse caso, se o `CONSUMED#<eventId>` já
existe, o evento é descartado. Nos demais casos, grava o resultado tardio (§4)
com o `CONSUMED`: `DUPLICATE` quando o waiter já retomou com outro resultado, `LATE` quando expirou ou
o resultado já é terminal. Depois do `SendTaskSuccess`, marca `DONE` com a condição
`status = RESUMING`. `TaskTimedOut` ou `InvalidToken` marcam `EXPIRED`, também só com
`status = RESUMING`, para nunca rebaixar um `DONE`. Por fim, atualiza o resumo do `META` (§4).

**Enriquecimento.** O `enrichmentWorker` trata a reentrega (abaixo), confere o `deadlineAt` antes de
qualquer chamada (§9) e calcula a `queryKey`. Evidência válida no `REUSE` é `HIT`: uma transação grava o outbox `EnrichmentResolved` e o `CONSUMED` do pedido.

Sem evidência válida, o worker tenta a liderança. Uma transação, antes da chamada, grava:

- Update do `INFLIGHT`: `state = IN_FLIGHT`, `fencingVersion` incrementada, `leaderWorkRef`, `queryId`
  e `leaseUntil`, com a condição
  `attribute_not_exists(pk) OR state = COMPLETED OR leaseUntil < :agora`;
- Put da `QUERY` (`IN_FLIGHT`, `attribute_not_exists`) e da `ATTEMPT#0001` com `startedAt`;
- débito da quota (§9).

O líder chama o provedor sem transação aberta, com `clientReference = queryId`. A conclusão é uma
transação pequena:

- `EVIDENCE` imutável;
- `REUSE`, só se o resultado for reutilizável (`FOUND` ou `NOT_FOUND` declarado; `FAILED` nunca);
- Update do `INFLIGHT` para `COMPLETED`, com `evidenceId` e `resultType`, com a condição
  `fencingVersion = :minha`; o item fica como lápide, com TTL, em vez de ser apagado;
- `QUERY` `COMPLETED` e `ATTEMPT` com `finishedAt`;
- outbox próprio (`MISS`) e `CONSUMED` do pedido.

O fan-out `JOIN` fica fora dessa transação, porque uma `TransactWriteItems` aceita até 100 ações. Para
cada `FOLLOWER#<fencingVersion>#*`, lido com `ConsistentRead`, o líder faz Put do outbox com `eventId`
UUIDv5 de `queryId|workRef` e `attribute_not_exists`. Quando a reentrega do líder encontra o
`INFLIGHT` `COMPLETED` com o seu `queryId`, refaz o fan-out, que é idempotente.

Quem encontra liderança válida se registra como seguidor numa transação: `ConditionCheck` no
`INFLIGHT` (`state = IN_FLIGHT AND fencingVersion = :v`) e Put do `FOLLOWER#<v>#<workRef>`. Se a
transação for cancelada, o worker relê o `INFLIGHT`. Com `COMPLETED`, publica para si o resultado
gravado, inclusive `NOT_FOUND` e `FAILED`; com outra liderança em voo, repete o registro. Como líder e
seguidor escrevem ou conferem o mesmo `INFLIGHT`, nenhum seguidor fica sem resposta.

**Reentrega do pedido de enriquecimento.** Antes de procurar o `REUSE`, o worker confere o que o
mesmo `workRef` já gravou, nesta ordem:

1. A `QUERY` do seu `workRef` existe: ele foi líder e nunca se registra como seguidor de si mesmo. Com
   `COMPLETED`, refaz o fan-out. Com `IN_FLIGHT`, não repete a chamada: passa a `QUERY` para `UNKNOWN`
   se houver tentativa aberta (`FAILED` se não houver) e conclui a liderança com `FAILED`, se a
   `fencingVersion` ainda for a sua.
2. O `CONSUMED` do pedido existe: o `HIT` já foi publicado.
3. Existe `FOLLOWER#<v>#<workRef>` na liderança atual: com `COMPLETED`, publica para si o resultado
   gravado, com o mesmo `eventId` do fan-out; com `IN_FLIGHT`, não faz nada, porque o fan-out o
   alcança.

O fan-out lê todos os `FOLLOWER#` da `queryKey`, de qualquer `fencingVersion`: quando uma lease vence
e outra mensagem assume a liderança (v+1), o novo líder também responde aos seguidores registrados na
versão superada. O `eventId` determinístico por `queryId` e `workRef` evita respostas duplicadas.

A conclusão do líder e o fan-out sempre conferem a `fencingVersion` (T15). Lease expirada não autoriza
repetir a chamada sem consultar o estado primeiro (M4).

**Seguradora.** O `insurerWorker` confere o `deadlineAt` (§9), deriva o `queryId` do `workRef` e lê a
`QUERY` antes de qualquer chamada:

| Estado da `QUERY` | Ação |
|---|---|
| Ausente | Segue os três passos abaixo |
| `COMPLETED` ou `FAILED` | Nada: o outbox do resultado já existe |
| `IN_FLIGHT` com `providerRequestId` | Refaz a metade do worker no `CORR#`, que é idempotente |
| `IN_FLIGHT` com tentativa aberta, sem resposta | Passa para `UNKNOWN` e consulta a situação (`GET /sim/insurers/beta/quotes/{ref}`); sem API de situação, como na alpha, fica `UNKNOWN` para a reconciliação e nunca repete a chamada |
| `IN_FLIGHT` sem tentativa aberta (só falhas antes do envio) | Segue com nova `ATTEMPT` se ainda couber em `workerRetries` e no `deadlineAt`; senão, `UNAVAILABLE` (§9) |
| `UNKNOWN` | Nada: a reconciliação cuida (M4) |

1. Antes do HTTP, uma transação grava a `QUERY` (`IN_FLIGHT`, `attribute_not_exists`,
   `clientReference = queryId`), a `ATTEMPT#<nnnn>` com `startedAt` e o débito da quota (`ADD used :1`
   com a condição `attribute_not_exists(used) OR used < :limit`, porque o item da janela pode não
   existir ainda). Sem quota, segue o §9.
2. Chamada HTTP, com `clientReference` quando o protocolo aceita. O callback que devolve o
   `clientReference` encontra a `QUERY#<queryId>` direto.
3. Resposta síncrona: uma transação grava a `EVIDENCE`, a `QUERY` `COMPLETED`, a `ATTEMPT` com
   `finishedAt`, o outbox `InsurerResultRecorded` (`eventId` = UUIDv5 do `queryId`) e o `CONSUMED` do
   pedido. Resposta `202`: uma transação grava o `providerRequestId` na `QUERY` e na `ATTEMPT`;
   depois o worker faz a sua metade do encontro no `CORR#`.

O `CONSUMED` é gravado na mesma transação do efeito, nunca antes.

**Callbacks.** A API confere a assinatura `X-Signature` (`sha256=<hex do HMAC>`) e grava a `INBOX`
com `attribute_not_exists`; callback duplicado é reconhecido pela inbox. Com `clientReference`, a API
encontra a `QUERY` e processa. Só com `providerRequestId`, faz a sua metade do encontro no
`CORR#<provider>#<providerRequestId>`: `UpdateItem ADD pendingEvents :externalEventId`, criando o item
se faltar, com `ReturnValues = ALL_NEW`. A metade do worker, depois do `202`, é
`UpdateItem SET queryId` no mesmo item, também com `ALL_NEW`. Quem enxergar as duas metades
(`queryId` e `pendingEvents`) processa as pendências.

O processamento é idempotente: uma transação passa a inbox de `RECEIVED` para `PROCESSED`, com
condição, e grava `EVIDENCE`, `QUERY` `COMPLETED` e o outbox `InsurerResultRecorded`. Se a `QUERY`
já estiver `COMPLETED` (resultado obtido por outro callback ou pela reconciliação), a transação só
passa a inbox para `PROCESSED`, sem novo outbox, para a inbox não ficar parada em `RECEIVED`. Callback sem
correlação depois de `unmatchedCallbackAfterSeconds` vira `UNMATCHED` (`gsi2pk = INBOX_UNMATCHED`),
visível ao `reconciler` e ao alarme (T12).

**Reconciliação manual.** O `POST /v1/operations/queries/{id}/reconcile` confere que o
`QUERYREF#<queryId>` pertence à organização do operador; senão, responde 404. Depois grava, numa
transação: `IDEMPOTENCY` (`RECONCILE`, escopo `queryId`), `OPERATION#<operationId>` (`QUEUED`) e o
outbox `QueryReconcileRequested`. O `reconcileWorker` confere que a `QUERY` é da mesma organização,
reconcilia e publica `QueryReconciled`. O `GET /v1/operations/{operationId}` responde 404 para
operação de outra organização. A provider entra no M4; a API, no M5.

## 9. Falhas, retries e orçamento de tempo

Classificação das respostas por protocolo:

| Fornecedor | Resposta | Classificação |
|---|---|---|
| Enriquecimento | 200 | `FOUND` |
| Enriquecimento | 404 declarado pelo provedor, com `notFoundRefs` | `NOT_FOUND` |
| Enriquecimento | 5xx ou erro de conexão antes do envio | Transitório; esgotado o orçamento, `FAILED` |
| Enriquecimento | 4xx de autenticação ou payload inválido | `FAILED`, nunca `NOT_FOUND` (RN07) |
| Enriquecimento | Timeout de leitura depois do envio | `FAILED` para a quote, sem repetição; a `QUERY` fica `UNKNOWN` |
| Seguradora | Oferta | `QUOTED` |
| Seguradora | Recusa | `DECLINED`, sem retry |
| Seguradora | Pendência de dados | `NEEDS_INFORMATION` |
| Seguradora | 5xx ou erro de conexão antes do envio | Transitório; esgotado o orçamento, `UNAVAILABLE` |
| Seguradora | Timeout de leitura depois do envio | `UNKNOWN`, sem repetição |

**Dono do retry.** O worker é o único dono do retry: em processo, até `workerRetries` (2), com backoff
exponencial e jitter. Cada retry grava nova `ATTEMPT` e reserva quota. O cliente HTTP fica sem retry
próprio, e a máquina não repete as esperas; o `Retry` do §6 cobre só falhas de invocação da Lambda.

**Prazo.** O worker confere o `deadlineAt` do pedido antes de cada chamada, de cada retry e no
reprocessamento. Com o prazo vencido, grava resultado terminal sem chamar: `UNAVAILABLE` com
`reasonCode = DEADLINE_EXCEEDED_BEFORE_CALL` na seguradora, `FAILED` no enriquecimento.

**Quota.** A reserva condicional vale para a seguradora e para o líder do enriquecimento, só no `MISS`;
`HIT` e `JOIN` não consomem. Sem quota, o worker reenvia a mensagem com `DelaySeconds` até a virada da
janela, se ainda couber no `deadlineAt`, e apaga a original, para não gastar `maxReceiveCount`. Se não
couber, grava `UNAVAILABLE` com `QUOTA_EXHAUSTED` (`FAILED` no enriquecimento), sem chamada.

**Invariantes de tempo.** Os valores estão no §12.

- Timeout da função do worker ≥ `providerHttpTimeoutSeconds` × 3 + `retryBackoffMaxSeconds`.
- `enrichmentLeaseSeconds` > timeout da função do `enrichmentWorker`.
- Toda fila tem `VisibilityTimeout` explícito, maior ou igual ao timeout da função que a consome. A AWS
  valida isso ao criar o ESM e recomenda cerca de 6 vezes. O Floci não valida (§13), então o teste de
  configuração do M1 confere a relação. Cada fila tem `maxReceiveCount` próprio.
- `enrichmentTimeoutSeconds` + ceil(`maxInsurers` / `mapConcurrency`) × `insurerTimeoutSeconds` +
  `stepsMarginSeconds` ≤ `quoteDeadlineSeconds`. Um teste de configuração valida a invariante, e o
  `select-insurers` limita a lista a `maxInsurers`. Ela atende ao "limitado pelo prazo restante" do
  PRD (§9), já que o Floci ignora `TimeoutSecondsPath`.

Um crash do worker só é reentregue depois do `VisibilityTimeout`, que passa do prazo da etapa. A etapa
termina em `TIMED_OUT`, e o resultado que chegar depois vira evidência tardia.

**Conflitos de transação.** `TransactionConflict` é repetido no código, com backoff e jitter, porque o
SDK não repete esse caso. O Floci não emula o conflito (§13): esse caminho tem teste unitário e é
repetido no M6.

**DLQs e reprocessamento.** Toda fila de trabalho tem DLQ, inclusive a `sim-callbacks`. A
`stream-failures` é só destino de falha do stream: não tem consumidor nem é reprocessada (§7). O
`reconciler` de cada solução reprocessa as DLQs no padrão do ADR 0007: abaixo de `dlqMaxAttempts`, reenvia a mensagem à
fila de origem, mas só antes do `deadlineAt` (do evento ou da versão). A idempotência por `queryId` e
por `workRef` impede chamada ou resultado duplicado (T31). Depois do limite ou do prazo, apaga a
mensagem e registra uma ocorrência `DEAD_LETTER`. O reprocessamento fica no `reconciler` para não
criar outra função.

**Reconciliação (M4).** O `reconciler` da quote, a cada minuto:

- republica `OUTBOX_PENDING` mais antigo que `outboxRepublishAfterSeconds` (já no M1);
- retoma waiters `RESUMING` parados com o `resumePayload` (T13);
- finaliza versões `ACTIVE_VERSION` cuja execução terminou em `TIMED_OUT`, `FAILED` ou `ABORTED`
  (`DescribeExecution` pelo `executionArn`): uma transação condicionada à `revision` passa os `RESULT`
  em `PENDING` ou `PROCESSING` para `TIMED_OUT`, consolida com os resultados existentes sem apagar
  ofertas, grava `TERMINAL` e o outbox `QuoteCompleted` (T20);
- grava as ocorrências da quote (§3) e repara o resumo do `META`;
- reprocessa a `results-dlq` e, a partir do M5, a `issues-dlq`.

O `reconciler` da provider, a cada minuto: republica `OUTBOX_PENDING` (já no M1); trata leases
vencidas; passa para `UNKNOWN` as consultas `QUERY_IN_FLIGHT` além do `deadlineAt`, como as da
`vehicle-beta-silent`; consulta a situação das consultas `UNKNOWN` quando há API de situação e, sem
ela, abre `UNKNOWN_QUERY`; processa inbox `RECEIVED` parada e marca `UNMATCHED`, com
`UNMATCHED_CALLBACK`; abre `STALE_OUTBOX` para outbox envelhecida; e reprocessa as DLQs.

**Alarmes (M4).** Declarados como `AWS::CloudWatch::Alarm` nas stacks das soluções, com os limiares
do §12. Workers e reconcilers publicam as métricas próprias em EMF.

| Alarme | Métrica |
|---|---|
| Idade e volume de cada DLQ e da `stream-failures` | `ApproximateAgeOfOldestMessage`, `ApproximateNumberOfMessagesVisible` |
| Outbox envelhecida | EMF `OutboxPendingAgeSeconds` |
| Callbacks `UNMATCHED` | EMF `UnmatchedCallbacks` |
| Consultas `UNKNOWN` | EMF `UnknownQueries` |
| Tarefas expiradas | EMF `ExpiredTasks` |
| Falhas por provedor | EMF `ProviderFailures` e `ProviderCalls`, por provedor |

O Floci só reconhece o tipo do recurso. O disparo dos alarmes é verificado no M6.

## 10. Simuladores e cenários

| Rota | Comportamento |
|---|---|
| `POST /sim/enrichment/profiles` | Perfil do veículo e do condutor; 404 `NOT_FOUND` com `notFoundRefs: ["vehicleRef"]` para veículo desconhecido; 503 nas duas primeiras chamadas de cada referência de pedido para `vehicle-flaky`; 504 sempre para `vehicle-enrichment-down` |
| `POST /sim/insurers/alpha/quotes` | Seguradora síncrona: oferta; `DECLINED` para `vehicle-alpha-declines`; 503 para `vehicle-alpha-down` |
| `POST /sim/insurers/beta/quotes` | Seguradora assíncrona: recebe `clientReference`, responde `202` com `providerRequestId` e agenda o callback, com o `clientReference`, na fila `sim-callbacks` |
| `GET /sim/insurers/beta/quotes/{ref}` | Situação atual por `providerRequestId` ou `clientReference`, para reconciliação (M4) |

Cenários da beta pelo veículo: oferta normal; `vehicle-beta-declines`; `vehicle-beta-silent` (sem
callback, gera `TIMED_OUT`); `vehicle-beta-duplicate` (callback enviado duas vezes);
`vehicle-beta-late` (callback depois do prazo); `vehicle-beta-needs-info`; `vehicle-beta-early`
(callback enviado antes da resposta `202`, só com o `providerRequestId`, para exercitar o encontro no
`CORR#` e o T12 no M3).

O estado dos simuladores fica na tabela `provider-solution-<stage>-simulators`, com TTL, que só a
Lambda `simulators` acessa. O contador do `vehicle-flaky` é por referência do pedido (o
`clientReference`, que é o `queryId` enviado pelo worker), então cada execução começa nova. A situação
da beta fica por `providerRequestId`, com um item auxiliar por `clientReference`.

| Cenário | Estado de que depende |
|---|---|
| `vehicle-flaky` | Contador por `clientReference` na tabela dos simuladores |
| Toda a beta | Situação por `providerRequestId`, para o GET de reconciliação |
| `vehicle-beta-duplicate`, `vehicle-beta-late`, `vehicle-beta-early` | Callbacks agendados na fila `sim-callbacks` |
| T04, T05 e T06 (reuso) | `REUSE#` e `INFLIGHT#` da provider: o harness do e2e apaga os das `queryKeys` dos fixtures antes de cada caso, pelo DynamoDB do Floci |

Regras de laboratório:

- Elegibilidade (`elig-1`, só dados de entrada): veículo com `salvage = true` é `INELIGIBLE`
  (`SALVAGE_VEHICLE`); condutor sem `licenseDate` é `NEEDS_INFORMATION` (`missingFields` com
  `risk.principalDriverRef`, `MISSING_LICENSE_DATE`).
- Enriquecimento obrigatório: `NOT_FOUND` vira `NEEDS_INFORMATION` com `ENRICHMENT_NOT_FOUND`. Cada
  item de `notFoundRefs` vira um `missingFields`: `vehicleRef` → `risk.vehicleRef`,
  `principalDriverRef` → `risk.principalDriverRef`.
- Seleção (`sel-1`, com dados enriquecidos): veículo com ano anterior a 2000 não tem seguradora
  (`NO_APPLICABLE_INSURER`); uso `COMMERCIAL` só cota na alpha; os demais cotam na alpha e na beta.
- O `label` de cada `missingFields` vem de uma tabela `path → label` na `quote-domain`.

Os veículos, condutores, clientes e perfis de cobertura de cada organização (`org-acme` e
`org-globex`) ficam em `contracts/fixtures/catalog.json`. O arquivo é empacotado no `quote-api` como
recurso de classpath (um `<resource>` apontando para `contracts/fixtures`) e a API o lê do classpath.
Um teste abre o `-lambda.zip` e confere a entrada.

## 11. Autenticação

A API valida access tokens do user pool Cognito da stack com smallrye-jwt:

```properties
mp.jwt.verify.issuer=${JWT_ISSUER}
mp.jwt.verify.publickey.location=${JWT_ISSUER}/.well-known/jwks.json
smallrye.jwt.path.groups="cognito:groups"
smallrye.jwt.required.claims=token_use,client_id
```

- `JWT_ISSUER` por stage: no local, `http://floci:4566/<poolId>`, igual ao `issuer` do
  `openid-configuration`; na AWS, `https://cognito-idp.<region>.amazonaws.com/<poolId>`, via
  `Fn::Sub`.
- `mp.jwt.verify.audiences` não é definido, porque o access token do Cognito não tem `aud`.
- ID tokens são rejeitados: `smallrye.jwt.required.claims` exige `token_use` e `client_id`, e um
  `SecurityIdentityAugmentor` confere `token_use = access` e `client_id` igual ao app client.
- A organização vem do grupo `org_<organizationId>`. Os grupos `perm_quotes_read`,
  `perm_quotes_write`, `perm_offers_select`, `perm_operations_read` e `perm_operations_reconcile`
  viram as permissões do `GET /v1/me` (`quotes:read`, `quotes:write`, `offers:select`,
  `operations:read` e `operations:reconcile`).
- O Floci publica `iss` e `jwks_uri` com o host do `FLOCI_HOSTNAME` (`floci`), mesmo quando o token
  é pedido por `localhost`. Na Lambda, que roda na rede docker, os dois resolvem direto.
- Nos `@QuarkusTest`, o issuer é lido do `openid-configuration` do container, e
  `mp.jwt.verify.publickey.location` é sobrescrito com a URL do JWKS na porta mapeada, porque o
  `FlociResource` sobe sem `FLOCI_HOSTNAME` e o host do `jwks_uri` publicado pode não resolver fora
  do container.
- Os testes ponta a ponta criam usuários com as APIs administrativas do Cognito e obtêm os tokens com
  `InitiateAuth` (`USER_PASSWORD_AUTH`) no host; quem os valida é a Lambda da API.

O `GET /v1/operations/issues` consulta o `gsi1` com `gsi1pk = ISSUES#<organizationId do operador>`,
com `type` e `status` em `FilterExpression`, sem `Scan`. Callback sem correlação não tem organização.
No MVP, ele não é listado para nenhuma organização, porque o contrato não tem papel de operador da
plataforma; aparece no alarme de callbacks `UNMATCHED` e no log da provider. `RECONCILE` e
`GET /v1/operations/{operationId}` conferem a organização do operador (§8).

## 12. Parâmetros por stage

| Parâmetro | local | Uso |
|---|---|---|
| `quoteDeadlineSeconds` | 300 | Prazo total da versão (`deadlineAt`) |
| `workflowMarginSeconds` | 60 | Margem somada ao `TimeoutSeconds` da máquina (rede de segurança) |
| `enrichmentTimeoutSeconds` | 20 | Espera do enriquecimento (ver nota) |
| `insurerTimeoutSeconds` | 20 | Espera por seguradora (90 na AWS; ver nota) |
| `mapConcurrency` | 2 | Itens simultâneos do Map |
| `maxInsurers` | 2 | Seguradoras por versão, no máximo |
| `stepsMarginSeconds` | 30 | Margem dos passos na invariante de orçamento |
| `providerHttpTimeoutSeconds` | 5 | Timeout de conexão e leitura por chamada |
| `workerRetries` | 2 | Retries do worker, além da tentativa inicial |
| `retryBackoffMaxSeconds` | 3 | Soma máxima dos backoffs (exponencial com jitter) |
| `enrichmentLeaseSeconds` | 30 | Lease da liderança |
| `inflightTombstoneSeconds` | 3600 | TTL da lápide do `INFLIGHT` |
| `enrichmentValiditySeconds` | 900 | Validade do enriquecimento simulado |
| `offerValiditySeconds` | 1800 | Validade da oferta simulada |
| `providerQuotaPerMinute` | `enrichment-sim`: 30, `alpha`: 30, `beta`: 30 | Quota por provedor e credencial |
| `betaCallbackDelaySeconds` | 3 | Atraso do callback simulado |
| `retryAfterSeconds` | 3 | Orientação de polling |
| `outboxRepublishAfterSeconds` | 30 | Idade a partir da qual o `reconciler` republica o outbox |
| `dlqMaxAttempts` | 3 | Reprocessamentos de DLQ (ADR 0007) |
| `unmatchedCallbackAfterSeconds` | 60 | Prazo para um callback sem correlação virar `UNMATCHED` |
| `stuckVersionMarginSeconds` | 120 | Margem além do `deadlineAt` para `STUCK_VERSION` |
| Limiares de alarme | DLQ: 1 mensagem ou 300 s de idade; outbox: 120 s; `UNMATCHED`, `UNKNOWN` e tarefas expiradas: 1; falhas por provedor: 50% em 5 min | Alarmes (M4) |

| Fila | Consumidor | Timeout da função | `VisibilityTimeout` | `maxReceiveCount` |
|---|---|---|---|---|
| quote `results` | `resume` | 15 s | 90 s | 5 |
| quote `issues` | `issue-projector` | 15 s | 90 s | 5 |
| provider `enrichment-requests` | `enrichmentWorker` | 25 s | 150 s | 3 |
| provider `insurer-requests` | `insurerWorker` | 25 s | 150 s | 3 |
| provider `reconcile-requests` | `reconcileWorker` | 25 s | 150 s | 3 |
| provider `sim-callbacks` | `simCallbackSender` | 15 s | 90 s | 5 |

Conferência das invariantes do §9 com esses valores: 5 × 3 + 3 = 18 s ≤ 25 s; lease de 30 s > 25 s;
visibilidade de 6 vezes o timeout; 20 + 1 × 20 + 30 = 70 s ≤ 300 s no local e 20 + 1 × 90 + 30 =
140 s ≤ 300 s na AWS.

Nota: no M1, medir no Floci o tempo da cadeia do enriquecimento (stream da quote, fila de pedidos,
stream da provider e fila de resultados, com os cold starts). Se ela não couber em 20 s, usar
`enrichmentTimeoutSeconds` e `insurerTimeoutSeconds` de 45 a 60 s no stage `local`; a invariante
continua valendo (60 + 60 + 30 ≤ 300). O Floci remove o container de uma Lambda depois de 300 s sem
invocação (§13), então a medição separa a primeira execução, com cold start, das seguintes, e os
cenários ponta a ponta não podem supor funções aquecidas.

## 13. Limites do Floci 2.4.0

Comportamentos verificados nas revisões do desenho e no spike do M0. "Não verificado" marca o que
nenhum teste cobriu ainda; nessas linhas, a coluna do meio diz a alternativa caso o Floci não atenda. A
coluna M6 marca o que precisa ser repetido na AWS.

| Recurso | Resultado no Floci | M6 |
|---|---|---|
| `TransactWriteItems` com condição | Funciona; cancela tudo e informa o motivo por item | |
| `TransactWriteItems` com 101 ações | `ValidationException` | |
| `TransactionConflict` | Não emulado: 20 transações concorrentes no mesmo item terminam todas com sucesso | Repetir |
| `ReturnValuesOnConditionCheckFailure = ALL_OLD` em `TransactWriteItems` | Não verificado. O desenho não depende dele: o conflito de idempotência relê com `GetItem` e `ConsistentRead` (§8) | |
| `StartExecution` repetido com mesmo nome e entrada | Idempotente | |
| `lambda:invoke.waitForTaskToken` e `sqs:sendMessage.waitForTaskToken` | Funcionam | Repetir |
| `SendTaskSuccess` repetido / token inválido | `TaskTimedOut` / `InvalidToken` | |
| Map com `MaxConcurrency` e `ItemSelector` | Funciona | |
| `TimeoutSeconds` e `HeartbeatSeconds` fixos por estado | Funcionam; o timeout do estado é capturável por `Catch` | Repetir |
| `TimeoutSeconds` da máquina | Encerra a execução em `TIMED_OUT` sem passar por `Catch` | Repetir |
| `TimeoutSecondsPath` | Ignorado: a execução fica parada; não usar | |
| SQS e DynamoDB Streams acionando Lambda | Funcionam; cada salto de ESM leva cerca de 1 s | |
| Vazão do ESM de stream | Cerca de 1 lote por segundo: com `BatchSize` 1, 20 registros levaram 20 s; com 100, 1 s | Repetir |
| Retry padrão do ESM de stream | Bloqueia o shard: o registro com erro é reinvocado e os seguintes esperam; com `MaximumRetryAttempts`, ele é descartado e o shard segue (na AWS, o padrão repete por até um dia) | Repetir |
| `FilterCriteria` no ESM de stream | Funciona: com o filtro de `INSERT` e `type = OUTBOX`, só os `INSERT` do outbox chegam; `MODIFY`, `REMOVE` e outros tipos nem invocam a função | |
| `ReportBatchItemFailures` no ESM de stream | Funciona como na AWS: o lote volta a partir do registro que falhou, inclusive os seguintes, e os anteriores não voltam | |
| `MaximumRetryAttempts`, `BisectBatchOnFunctionError` e `onFailure` em SQS no ESM de stream | Funcionam: o lote é dividido até isolar o registro com erro, que é tentado até o limite e vai para a fila de falha; o shard segue. A mensagem de falha só traz shard e faixa de sequência, sem o conteúdo, e os registros vizinhos são reentregues várias vezes | Repetir |
| `ReportBatchItemFailures` no ESM de SQS | Funciona: só a mensagem que falhou é reentregue | |
| `RedrivePolicy` para a DLQ | Funciona, com e sem Lambda: a mensagem vai para a DLQ depois de `maxReceiveCount` recebimentos | |
| Reentrega de SQS | Cerca de `VisibilityTimeout` + 5 s (intervalo de polling do ESM) | |
| `VisibilityTimeout` × timeout da função no ESM de SQS | Não validado: o Floci aceita visibilidade menor que o timeout, e a AWS rejeita com `InvalidParameterValueException`. O teste de configuração confere a relação (§9) | Repetir |
| `DelaySeconds` por mensagem | Funciona: com 12 s de atraso, a mensagem chegou à função em 13 s, contra 1 s sem atraso | |
| Regra do EventBridge com SQS ou Lambda como alvo | Funciona | |
| EventBridge Scheduler | `rate(5 minutes)` dispara (ADR 0007). `rate(1 minute)` não foi verificado; o primeiro teste do republicador, no M1, confere. Se não disparar, o `reconciler` roda a cada 5 min e o `outboxRepublishAfterSeconds` não muda | |
| `/v1/{proxy+}` e `/sim/{proxy+}` em `AWS_PROXY` | Não verificado; rota com parâmetro de caminho funciona (receipt). Se `{proxy+}` falhar, declarar uma rota por recurso do contrato | |
| Lambda chamando o API Gateway da própria stack (`PROVIDERS_BASE_URL`) | Não verificado; as Lambdas já alcançam `http://floci:4566` para as APIs AWS, falta o caminho `_user_request_`. Se falhar, o cliente de fornecedor e o `simCallbackSender` ganham, só no stage `local`, uma implementação que invoca a Lambda de destino com um evento de proxy do API Gateway | |
| `serverless-step-functions`: IAM de `waitForTaskToken` e `TimeoutSeconds` padrão em `lambda:invoke` | Não verificado. O desenho declara `TimeoutSeconds` em toda tarefa (§6) e não depende do padrão | Repetir (IAM) |
| `iss` do Cognito | `http://floci:4566/<poolId>`, com o host do `FLOCI_HOSTNAME` mesmo em chamada por `localhost`; igual ao `issuer` do `openid-configuration`. O `jwks_uri` usa o mesmo host. Tokens `RS256` com `kid = <poolId>`; a assinatura confere com o JWKS | Repetir (issuer da AWS) |
| Claims dos tokens do Cognito | Access token: `token_use = access`, `client_id`, `cognito:groups`, sem `aud`. ID token: `token_use = id`, `aud = client_id`, `cognito:groups`. `InitiateAuth` com `USER_PASSWORD_AUTH` devolve tokens sem challenge para usuário `CONFIRMED` | |
| Containers de Lambda ociosos | Removidos depois de 300 s sem invocação (verificação a cada 30 s); a invocação seguinte paga o cold start. Chave `floci.services.lambda.container-idle-timeout-seconds`; a variável de ambiente equivalente não foi testada | |
| CloudFormation de Cognito, KMS, Scheduler, alarmes e EventSourceMapping | Tipos reconhecidos; o alarme não dispara | Repetir (disparo dos alarmes) |

## 14. Testes por marco

| Marco | Casos do PRD | Onde |
|---|---|---|
| M1 | T01 (uma seguradora), T02, T03, T19, T23, T24 | Unitários no `domain`, integração com Floci em `api` e `functions`, `quote-e2e` |
| M2 | T04, T05, T06, T15, T25, T27 | Integração com Floci em `provider-functions`, `quote-e2e` |
| M3 | T01, T07, T08, T11, T12, T21, T26 | `provider-functions`, `provider-api`, `quote-e2e` |
| M4 | T09, T10, T13, T14, T16, T17, T20, T31 | `quote-functions`, `provider-functions`, `quote-e2e` |
| M5 | T18, T22, T28, T29, T30 | Contrato em `quote-api` contra `contracts/openapi.yaml`, `quote-e2e` |
| M6 | Itens marcados no §13 | Stage `dev` na AWS |

Além dos casos do PRD:

- M1: republicador mínimo (`reconciler`) nas duas soluções, com o disparo do agendamento; relay
  registro a registro, com reentrega por `ReportBatchItemFailures`; teste de configuração das
  invariantes de orçamento e de visibilidade × timeout; desserialização de `SQSEvent` e
  `DynamodbEvent` na provider; catálogo dentro do `-lambda.zip`; issuer lido do
  `openid-configuration` nos `@QuarkusTest`; medição da cadeia do enriquecimento; rotas `{proxy+}` e
  chamada ao API Gateway da própria stack, ainda não verificadas (§13).
- M2: o harness limpa `REUSE#` e `INFLIGHT#` antes de T04, T05 e T06.
- M3: T12 com `vehicle-beta-early`; T21 também no provedor de enriquecimento.
- M4: o `POST /v1/quotes/{id}/versions` entra no M4, porque o T17 está no M4; o bloqueio de seleção em
  versão substituída é testado na integração da quote. Também entram o reprocessamento das DLQs, as
  ocorrências da quote e os alarmes (só o tipo do recurso no Floci).
- M5: endpoint de seleção completo.
- M6: task token, callbacks, IAM, timeouts, `TransactionConflict`, retry e divisão de lote do stream,
  visibilidade × timeout, issuer do Cognito, recuperação e disparo dos alarmes.

Cada teste leva o identificador do caso no nome (por exemplo, `t02_resubmissionReturnsSameQuote`).
