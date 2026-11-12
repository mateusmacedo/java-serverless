# Plataforma de cotação: desenho técnico

Este documento detalha como o [PRD de backend](prds/PRD_Cotacao_Seguros_Backend_v2.md) é implementado
no monorepo. As decisões estão nos ADRs [0013](decisions/0013-barramento-da-plataforma.md),
[0014](decisions/0014-cotacao-em-duas-solucoes-e-task-token.md) e
[0015](decisions/0015-modelo-dynamodb-e-outbox-da-cotacao.md). O contrato da API de produto é o
`contracts/openapi.yaml`; os eventos estão em `docs/events/quote-solution.asyncapi.yaml` e
`docs/events/provider-solution.asyncapi.yaml`; o callback das seguradoras assíncronas, em
`docs/api/provider-solution.openapi.yaml`.

## 1. Módulos

```text
platform/events/              serverless.yml do barramento platform-<stage>-events
quote-solution/               parent quarkus-parent
  domain/   quote-domain      regras, estados, consolidação, idempotência, rótulos de missingFields (Java puro, sem SDK AWS)
  api/      quote-api         API de produto (quarkus-amazon-lambda-rest + smallrye-jwt); catálogo no classpath
  functions/quote-functions   workflow-steps, dispatch, resume, outbox-relay, issue-projector, reconciler
  e2e/      quote-e2e         cenários ponta a ponta da plataforma no Floci, com as duas soluções implantadas (perfil e2e)
  serverless.yml
provider-solution/            parent spring-parent
  domain/   provider-domain   chave de reuso, validade, liderança, quota, classificação de respostas, assinatura de callbacks, contratos de provedor
  api/      provider-api      função api: POST /v1/providers/{provider}/callbacks (Spring MVC + serverless-java-container)
  functions/provider-functions workers, outboxRelay, reconciler (Spring Cloud Function + aws-lambda-java-events)
  simulators/provider-simulators seguradora síncrona, seguradora assíncrona e provedor de enriquecimento (Spring MVC);
                               simCallbackSender, com handler próprio e sem Spring
  serverless.yml
contracts/openapi.yaml        API de produto; contracts/fixtures/*.json (o catálogo também vai para o classpath do quote-api)
```

Pacotes Java: `com.mmda.serverless.quote.{domain,api,functions}` e
`com.mmda.serverless.provider.{domain,api,functions,simulators}`. Os `artifactId` levam o prefixo da
solução, como na receipt.

A provider não tem módulo `e2e` próprio: os cenários ponta a ponta das duas soluções ficam no
`quote-e2e`, que exercita a provider implantada junto com a quote (§14).

O `provider-api` (M3) é a API de callbacks das seguradoras assíncronas: Spring MVC atrás do
`serverless-java-container`, na função `api`, com a rota `POST /v1/providers/{provider}/callbacks` (§7
e §8). O `simCallbackSender`, que entrega os callbacks agendados pela beta simulada, fica no
`provider-simulators`, e não no `provider-functions`, porque faz parte do fornecedor simulado, não da
plataforma. Ele tem handler próprio, sem Spring Cloud Function e sem contexto Spring: o contexto custava
cerca de 4,6 s de partida a frio, dentro da espera de 20 s da seguradora no stage `local` (§12).

## 2. Recursos por stack

| Stack | Recurso | Nome |
|---|---|---|
| platform | Barramento | `platform-<stage>-events` |
| quote | Tabela | `quote-solution-<stage>-quotes` (stream `NEW_IMAGE`, índices `gsi1` e `gsi2`) |
| quote | Fila de resultados + DLQ | `quote-solution-<stage>-results`, `quote-solution-<stage>-results-dlq` |
| quote | Fila de ocorrências + DLQ | `quote-solution-<stage>-issues`, `...-issues-dlq` (M5; consumida pelo `issue-projector`, §3 e §7) |
| quote | Fila de falhas do stream | `quote-solution-<stage>-stream-failures` (destino `onFailure` da relay) |
| quote | Máquina de estado | `quote-solution-<stage>-quote-flow` |
| quote | Segredo da cifra de tokens | `quote-solution/<stage>/token-key` |
| quote | User pool, client e grupos Cognito | `quote-solution-<stage>-users` |
| quote | Alarmes CloudWatch | `quote-solution-<stage>-<alarme>`: 8 alarmes (6 do M4 e 2 da `issues-dlq` no M5), sem `AlarmActions` (§9) |
| provider | Tabela | `provider-solution-<stage>-queries` (stream `NEW_IMAGE`, índice `gsi2`) |
| provider | Tabela dos simuladores | `provider-solution-<stage>-simulators` (TTL; acesso só da Lambda `simulators`) |
| provider | Bucket de respostas brutas | `provider-solution-<stage>-payloads` |
| provider | Filas + DLQs | `provider-solution-<stage>-enrichment-requests`, `...-insurer-requests`, `...-reconcile-requests` (M4) |
| provider | Fila de callbacks simulados (atraso) + DLQ | `provider-solution-<stage>-sim-callbacks`, `...-sim-callbacks-dlq` |
| provider | Fila de falhas do stream | `provider-solution-<stage>-stream-failures` (destino `onFailure` da relay) |
| provider | Segredo de assinatura de callbacks | `provider-solution/<stage>/callback-secret` |
| provider | Segredo da chave de reuso | `provider-solution/<stage>/query-key-secret` |
| provider | Alarmes CloudWatch | `provider-solution-<stage>-<alarme>`: 16 alarmes, sem `AlarmActions` (M4, §9) |

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

**Papéis IAM.** Na quote, cada função tem papel próprio, com as permissões do §7 restritas aos recursos
da stack. Na provider, as funções de trabalho (no M1, `enrichmentWorker`, `insurerWorker`,
`outboxRelay` e `reconciler`; no M4, também o `reconcileWorker`) dividem um papel, e cada ação dele é
restrita ao recurso que usa: tabela de consultas e índice `gsi2`, prefixo `raw/` do bucket de respostas,
barramento, segredo da chave de reuso e as filas de reenvio adiado e de falhas do stream. No M4, esse
papel ganhou `sqs:SendMessage` na `reconcile-requests` e na `sim-callbacks`, para o reprocessamento
devolver as mensagens das DLQs à fila de origem, e `sqs:ReceiveMessage`, `sqs:DeleteMessage` e
`sqs:GetQueueAttributes` nas 4 DLQs (§9). Três Lambdas têm papel próprio, cada uma com o que usa:

| Papel | Função | Permissões |
|---|---|---|
| `SimulatorsRole` | `simulators` | `GetItem`, `PutItem` e `UpdateItem` na tabela dos simuladores, que só ela acessa; `sqs:SendMessage` na `sim-callbacks`, onde a beta agenda os callbacks |
| `CallbacksApiRole` | `api` | `GetItem`, `PutItem`, `UpdateItem` e `Query` na tabela de consultas; `s3:PutObject` no prefixo `raw/` do bucket de respostas; leitura do `callback-secret` |
| `SimCallbackSenderRole` | `simCallbackSender` | `sqs:ReceiveMessage`, `sqs:DeleteMessage` e `sqs:GetQueueAttributes` na `sim-callbacks`, sem `sqs:ChangeMessageVisibility`: a mensagem que falha volta à fila pela visibilidade; leitura do `callback-secret` |

Só a `api` e o `simCallbackSender` leem o `callback-secret`; o papel das funções de trabalho não o lê. O
`ServerlessConfigurationTest` (`callbackRolesGrantOnlyWhatTheirCodeAndTheQueueConsumerUse`) confere que
o `CallbacksApiRole` e o `SimCallbackSenderRole` têm exatamente essas ações, além das de log.

Na quote, o M4 ampliou o papel do `reconciler` e o da API (§7). O M5 acrescentou o `IssueProjectorRole`,
do `issue-projector`, e ampliou de novo os papéis da API e do `reconciler` (§7).

**Variáveis do M4.** Todas as funções das duas soluções recebem `STAGE` e `METRICS_NAMESPACE`
(`mmda/provider-solution` e `mmda/quote-solution`), que as métricas EMF e os alarmes usam (§9). Na
provider, o `reconciler` e o `reconcileWorker` recebem também `PROVIDERS_BASE_URL`, porque consultam a
API de situação da beta (§8 e §9). Na quote, as variáveis do `reconciler` ficam no `environment` do
`provider` do `serverless.yml` (§7); o M5 acrescentou ali `ISSUES_QUEUE_URL` e `ISSUES_DLQ_URL`.

**Memória das funções.** Todas as funções de todas as soluções usam `memorySize: 1769`, declarado no
`provider` de cada `serverless.yml`; não há dimensionamento por função. A Lambda reparte CPU
proporcionalmente à memória, e 1.769 MB equivalem a 1 vCPU. No Floci, o valor é só o teto de memória do
container, porque o Floci não limita CPU; por isso os tempos medidos no Floci (§12) não dependem dele.

O Floci não aplica IAM, então cinco pontos ficam para o M6: o `states:SendTaskSuccess` restrito ao ARN
da máquina; o `dynamodb:ListStreams` da relay, que não aceita recurso específico e usa `*`; o papel
que o `serverless-step-functions` gera para a máquina chamar a `dispatch` com `waitForTaskToken`; os
papéis próprios da provider (tabela acima), inclusive os do M3, cuja restrição nenhum teste exercitou;
e as permissões que o M4 e o M5 acrescentaram nas duas soluções.

## 3. Eventos

Todo `detail` tem `eventId` (UUID), `occurredAt` (ISO-8601 UTC) e `schemaVersion` (`"1"`). Valores
monetários são strings decimais; datas com hora em UTC.

| detail-type | source | Campos do `detail` além do envelope |
|---|---|---|
| `QuoteAccepted` | mmda.quote | `quoteId`, `version`, `organizationId` |
| `EnrichmentRequested` | mmda.quote | `workRef`, `organizationId`, `quoteId`, `version`, `provider` (`enrichment-sim`), `operation` (`VEHICLE_DRIVER_PROFILE`), `contractVersion` (`1`), `params` (`vehicleRef`, `principalDriverRef`), `deadlineAt` |
| `InsurerQuoteRequested` | mmda.quote | `workRef`, `organizationId`, `quoteId`, `version`, `insurerId`, `risk` (cópia autorizada do snapshot: `product`, `usage`, `vehicle`, `principalDriver`, `coverageProfile` resolvido como objeto, com `code`, `label` e `coverages`, `desiredStartDate`), `enrichment` (resumo; `evidenceId` e `queryId` seguem os patterns da provider, `^ev_[0-9a-f]{32}$` e `^q_[0-9a-f]{32}$`), `deadlineAt` |
| `QuoteCompleted` | mmda.quote | `quoteId`, `version`, `outcome`, `outcomeReason`, `offerCount` |
| `OfferSelected` | mmda.quote | `quoteId`, `version`, `offerId`, `selectionId` |
| `QueryReconcileRequested` | mmda.quote | `operationId`, `organizationId`, `queryId`, `reason` (texto livre do operador, até 500 caracteres, só para auditoria), `requestedBy` (`sub` do user pool) (consumido pela provider no M4; publicado pela API da quote a partir do M5) |
| `EnrichmentResolved` | mmda.provider | `workRef`, `quoteId`, `version`, `queryId`, `evidenceId`, `reuseDecision` (`HIT`, `JOIN`, `MISS`), `policyVersion` (política de reuso que produziu a evidência, no formato `^[A-Za-z0-9][A-Za-z0-9._-]*$`), `resultType` (`FOUND`, `NOT_FOUND`, `FAILED`), `result` (perfil normalizado, quando `FOUND`), `notFoundRefs` (quando `NOT_FOUND`: referências que o provedor declarou não encontrar, só `vehicleRef` e `principalDriverRef`, como `["vehicleRef"]`), `reusableUntil` (só em `FOUND` e `NOT_FOUND`), `errorClass` (quando `FAILED`) |
| `InsurerResultRecorded` | mmda.provider | `workRef`, `quoteId`, `version`, `insurerId`, `queryId`, `attemptRef`, `status` (`QUOTED`, `DECLINED`, `NEEDS_INFORMATION`, `UNAVAILABLE`), `reasonCode`, `missingFields` (lista de `{path, reasonCode}`, com `path` relativo ao snapshot, como `risk.principalDriver.licenseDate`), `offer` (quando `QUOTED`: `externalOfferId`, `insurerName`, `premiumTotal`, `currency`, `installments`, `coverages` com `code`, `label` e `insuredAmount`, como o `OfferCoverage` do OpenAPI, `deductibles`, `validUntil`, `rawResponseRef`) |
| `QueryReconciled` | mmda.provider | `operationId`, `organizationId`, `queryId`, `status` (`SUCCEEDED`, `FAILED`), `safeSummary`, `revision` (sempre 2; M4) |
| `OperationIssueRaised`, `OperationIssueUpdated` | mmda.provider | `issueId`, `organizationId` (ausente em callback sem correlação, no `DEAD_LETTER` de mensagem sem organização no payload, como a da `sim-callbacks` e a ilegível, e no `STALE_OUTBOX` de evento sem consulta), `type` (`UNKNOWN_QUERY`, `UNMATCHED_CALLBACK`, `DEAD_LETTER`, `STALE_OUTBOX`; no item `ISSUE#`, fica em `issueType`), `status` (`OPEN`, `RESOLVED`), `queryId`, `quoteId`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable` (só `UNKNOWN_QUERY`), `revision` (M4) |

Nenhum evento leva token, credencial, CPF ou resposta bruta. O `workRef` é
`wr_` + os 32 primeiros caracteres hexadecimais do SHA-256 de `quoteId|version|kind|insurerId`
(`kind` = `ENRICHMENT` ou `INSURER`; `insurerId` vazio no enriquecimento). O `deadlineAt` de um
pedido é o instante da `dispatch` mais o `TimeoutSeconds` da etapa, limitado ao `deadlineAt` da
versão.

No `EnrichmentResolved`, `queryId`, `evidenceId` e `policyVersion` são sempre obrigatórios. O
`reuseDecision` diz como o pedido foi resolvido: `HIT` usou a evidência válida do índice de reuso,
`JOIN` esperou a consulta de outro pedido, e `MISS` teve consulta própria. No `HIT` e no `JOIN`, o
evento leva o `queryId`, o `evidenceId` e o `policyVersion` da evidência do líder. `MISS` também marca
a conclusão sem chamada, por prazo vencido ou quota esgotada: o pedido grava consulta própria, sem
liderança. No prazo vencido, isso vale mesmo com evidência válida no índice, porque o prazo é conferido
antes do `HIT` (§8).

O `InsurerQuoteRequested` não leva `policyVersion`: a seguradora não decide reuso, e a rastreabilidade
fica pelo `evidenceId` da evidência usada (§4).

O `eventId` é determinístico quando o mesmo fato pode ser gravado mais de uma vez. O outbox é gravado
com `attribute_not_exists`, então a repetição não cria um segundo evento. Os UUIDv5 das duas soluções
usam o mesmo namespace, o URL da RFC 9562 (`6ba7b811-9dad-11d1-80b4-00c04fd430c8`):

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
pedido registra a identidade do pedido e não substitui a leitura da consulta.

No pedido de enriquecimento, a chave do `CONSUMED` é sempre o UUIDv5 do `workRef`, o `eventId` que o
contrato da quote atribui ao pedido. Isso vale em todos os caminhos: líder, conclusão sem liderança,
`HIT`, `JOIN`, fan-out e reentrega. O `eventId` com que o pedido chegou nunca é usado, porque o fan-out
só conhece o `workRef` do seguidor. Assim o pedido é deduplicado pelo `workRef`: uma reentrega com
outro `eventId` encontra o mesmo `CONSUMED`. Na seguradora, o `CONSUMED` usa o `eventId` recebido, que
pelo contrato é o mesmo UUIDv5; a deduplicação ali é pela `QUERY` do `queryId`.

O `revision` de `OperationIssueRaised`, `OperationIssueUpdated` e `QueryReconciled` é monotônico por
`issueId` e por `operationId` e é mantido no item de origem da provider (§5). O `issue-projector` grava
com a condição `attribute_not_exists(revision) OR revision < :r` e descarta versões antigas.

O `QueryReconciled` sai uma vez por operação, com o resultado e `revision` 2. O `RUNNING`, revisão 1 do
`OPERATION#`, fica só na tabela da provider e não é publicado (§5). O `OperationIssueRaised` sai só na
primeira revisão da ocorrência. O `OperationIssueUpdated` sai em cada revisão seguinte: na resolução, na
reabertura e a cada novo descarte do mesmo pedido em `DEAD_LETTER`, porque cada mensagem descartada é
um fato novo. Nos outros tipos, observar de novo a ocorrência aberta não gera revisão, para o ciclo de
1 min não publicar um evento por minuto. Só `UNKNOWN_QUERY` é `reconcilable`: é a única ocorrência que a
reconciliação manual resolve; nas outras, o reparo é automático ou a mensagem já foi descartada.

No evento, o tipo da ocorrência vai no campo `type`. Nos itens `ISSUE#` das duas tabelas, ele fica no
atributo `issueType`, porque `type` é o tipo do item em toda a tabela (ADR 0015): `ISSUE` nas
ocorrências. O `issue-projector` do M5 grava o `type` do evento no `issueType` do item (§4 e §5).

**Consumo pela fila `issues` (M5).** A regra `ProviderIssuesRule` da quote leva o `QueryReconciled`, o
`OperationIssueRaised` e o `OperationIssueUpdated` à fila `issues`, que o `issue-projector` consome com
`batchSize: 1` e `ReportBatchItemFailures`. Ele lê o `detail` e projeta:

- `OperationIssueRaised` e `OperationIssueUpdated` viram o `ISSUE#<issueId>`, com um `UpdateItem` de
  condição `attribute_not_exists(revision) OR revision < :r`. O item recebe `source = provider`,
  `issueType`, `status`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision` e
  `projectedAt` e, quando o evento os traz, `organizationId` (com o `gsi1`), `queryId` e `quoteId`. A
  ocorrência projetada nunca entra no `gsi2`: quem a resolve é a provider, com um `OperationIssueUpdated`.
- A mesma transação grava o `QUERYREF#<queryId>` quando a ocorrência traz `queryId` e `organizationId`.
  É o único caminho do `QUERYREF#`, que autoriza o `RECONCILE` (§8). A gravação não tem condição e usa
  `if_not_exists` na organização e no `firstSeenAt`: não derruba a projeção e nunca troca a organização
  de uma consulta já conhecida.
- `QueryReconciled` vira o `OPERATION#<operationId>`: `status`, `safeSummary`, `revision` e `updatedAt`
  (o `occurredAt` do evento), com a condição `revision < :r` e a mesma organização. Antes, o projetor lê o
  `OPERATION#`: se ele é de outra organização, descarta o evento sem gravar nada, registra um `WARN` com o
  `eventId`, o `operationId` e as duas organizações e devolve `FOREIGN_ORGANIZATION`. A condição da
  transação continua como defesa. O `QueryReconciled` não grava `QUERYREF#`, porque o par
  `queryId`/`organizationId` dele é o do pedido e não prova que a consulta é da organização (AsyncAPI da
  provider).
- Toda projeção grava o `CONSUMED#<eventId>` na mesma transação. Uma transação cancelada por condição é
  descartada, com o motivo no log ("event already consumed" ou "revision not newer than the projected
  one"); outro cancelamento volta à fila.

O evento de outra `source` ou de outro `detail-type` é ignorado, só com log, e sai da fila. O evento
malformado (campo obrigatório ausente, tipo de ocorrência fora dos quatro da provider, `status` fora do
enum, instante ilegível ou `revision` menor que 1) lança erro, volta à fila e, depois de
`issuesMaxReceiveCount` recebimentos, vai para a `issues-dlq`, que o `reconciler` reprocessa (§9).

Um `QueryReconciled` de uma operação que a quote nunca gravou cria o `OPERATION#`, porque a condição
aceita `attribute_not_exists`. Sem `QUERYREF#`, isso não autoriza reconciliação, mas a organização do
evento passa a ler essa operação no `GET /v1/operations/{operationId}`. É um comportamento conhecido do
MVP, sem mudança.

A quote também grava ocorrências próprias, direto no `ISSUE#` e sem evento, pelo `reconciler` (M4):

- `WORKFLOW_FAILED`: execução `FAILED`, `TIMED_OUT` ou `ABORTED`, que o `reconciler` finaliza (§9).
- `TASK_EXPIRED`: waiter que expirou com um resultado gravado para ele (o `resumePayload`, ou o
  `resultEventId` no `RESULT` ou na evidência usada) e cuja execução encerrada não mostra a entrega no
  `GetExecutionHistory` (§8). Com a execução viva, o waiter fica no índice; sem `executionArn`, a
  ocorrência é aberta. O waiter que expirou sem resultado é o timeout normal da etapa: o `RESULT` fica
  `TIMED_OUT` com `STEP_TIMEOUT`, visível ao usuário, e o waiter só sai do índice, sem ocorrência. Sem
  essa regra, o alarme `expired-tasks` dispararia a cada timeout de seguradora (§9).
- `STUCK_VERSION`: `ACTIVE_VERSION` além de `deadlineAt` + `stuckVersionMarginSeconds`.
- `DEAD_LETTER`: mensagem apagada da `results-dlq` no limite de `dlqMaxAttempts`, ou na hora quando nem
  identifica a cotação, e, a partir do M5, da `issues-dlq` (§9). O prazo da versão não descarta.

Dessas, só a `STUCK_VERSION` se resolve sozinha (M5, §9). `WORKFLOW_FAILED`, `TASK_EXPIRED` e o
`DEAD_LETTER` da quote registram um fato e ficam `OPEN`, como o `DEAD_LETTER` da provider. Decisão do M5:
não há reconhecimento manual no MVP, porque ele exigiria mudar o contrato compartilhado dos dois PRDs; é
um limite conhecido, registrado também na descrição de `IssueStatus` no `contracts/openapi.yaml`.

Os AsyncAPI das duas soluções definem esses schemas, inclusive o de `missingFields` com paths
relativos ao snapshot. No M4, o da provider (0.4.0) ganhou a operação de consumo do
`QueryReconcileRequested` e exemplos capturados no Floci implantado: `QueryReconciled` (`succeeded` e
`failed`), `OperationIssueRaised` (`unknown-query`), `OperationIssueUpdated` (`resolved`) e o
`InsurerResultRecorded` concluído pela API de situação (`quoted-by-status-api`). O da quote passou a
0.2.0, com o exemplo `unknown-query` do `QueryReconcileRequested`, o pedido que gera o exemplo `failed`
do `QueryReconciled`.

No M5, o da provider passou a 0.5.0, só com descrições novas: o `QueryReconciled` e as ocorrências vão
para a fila `issues` da quote; o `type` do evento vira o `issueType` do item; o par
`queryId`/`organizationId` de uma ocorrência alimenta o `QUERYREF#`, e o do `QueryReconciled` é o do
pedido; o `quoteId` do `DEAD_LETTER` segue a regra do §9. O texto registra também que os exemplos de
`InsurerResultRecorded` com oferta são anteriores ao M5: hoje as coberturas vêm do perfil do
`catalog.json`, com `COMPREHENSIVE` quando o pedido não traz perfil, e a franquia é de 5% na alpha e de
4% na beta sobre a `COLLISION` (§10). Os exemplos não foram recapturados, porque o `quoted-by-callback`
está amarrado ao exemplo de callback de `docs/api/provider-solution.openapi.yaml` pelo `EventContractTest`.

O da quote passou a 0.3.0 e declara o que ela consome: os canais `resultsQueue`
(`quote-solution-{stage}-results`) e `issuesQueue` (`quote-solution-{stage}-issues`) e as operações
`receiveProviderResults`, `receiveQueryReconciled` e `receiveOperationIssue`, com as mensagens por `$ref`
ao AsyncAPI da provider. As descrições dizem quais campos a quote lê, como a `revision` e o `CONSUMED#`
descartam repetições, que o `QUERYREF#` só nasce das ocorrências, que o evento de outra organização é
descartado e que o malformado vai à `issues-dlq`. O `QueryReconcileRequested` ganhou `maxLength` 500 no
`reason`, e o exemplo traz um motivo em texto e um `sub` em `requestedBy`.

O `insurerName` do `RESULT` vem do registro de seguradoras da quote (`Insurers`, na `quote-domain`) e
é gravado pelo `select-insurers` (§4); o da `OFFER` vem do `offer.insurerName` que a seguradora
devolve. Os dois precisam ser iguais: `alpha` é "Alpha Seguros" e `beta`, "Beta Seguros". O
`ProviderEventsConsumptionTest` lê os exemplos do AsyncAPI da provider e confere a igualdade.

## 4. Tabela da quote-solution

| Item | `pk` | `sk` | Atributos principais |
|---|---|---|---|
| Cotação | `QUOTE#<quoteId>` | `META` | `organizationId`, `createdBy`, `currentVersion`, `createdAt`, `gsi1pk`, `gsi1sk`, resumo da versão atual (`phase`, `outcome`, `offerCount`, que só avançam desde o M5; abaixo), `selectionId`, `selectedVersion`, `selectedOfferId` (M5, gravados pela seleção, §8), `updatedAt` |
| Versão | `QUOTE#<quoteId>` | `V#<nnnn>` | `snapshot` (JSON), `inputHash`, `phase`, `outcome`, `outcomeReason`, `missingFields`, `eligibilityDecision`, `eligibilityReasonCode`, `eligibilityMissingFields`, `eligibilityRuleVersion`, `selectedInsurers`, `selectionRuleVersion`, `workflowVersion`, `executionName`, `executionArn`, `deadlineAt`, `supersededAt`, `revision`, `updatedAt`, `gsi2pk = ACTIVE_VERSION` enquanto não terminal; no `TERMINAL`, `gsi2pk = SUMMARY_PENDING`, com `gsi2sk` no instante da consolidação, até o `refreshSummary` gravar o resumo do `META` ou o `reconciler` conferi-lo e, se diverge, repará-lo (M4) |
| Resultado da seguradora | `QUOTE#<quoteId>` | `V#<nnnn>#RESULT#<insurerId>` | `insurerName`, `status` (`PENDING`, `PROCESSING` ou terminal), `reasonCode`, `missingFields`, `queryRef`, `attemptRef`, `resultEventId` (o `eventId` do resultado gravado pela retomada; M4), `revision`, `updatedAt` |
| Oferta | `QUOTE#<quoteId>` | `V#<nnnn>#OFFER#<offerId>` | `insurerId`, `insurerName`, `externalOfferId`, `premiumTotal`, `currency`, `installments`, `coverages`, `deductibles`, `validUntil`, `rawResponseRef`, `createdAt` |
| Seleção | `QUOTE#<quoteId>` | `V#<nnnn>#SELECTION` | `quoteId`, `version`, `selectionId`, `offerId`, `selectedAt`, `selectedBy` (o `sub` de quem selecionou); uma por versão, gravada com `attribute_not_exists` (M5, §8) |
| Evidência usada | `QUOTE#<quoteId>` | `V#<nnnn>#EVIDENCE#<workRef>` | `evidenceId`, `queryId`, `reuseDecision`, `resultType`, `policyVersion` (sempre presente: evento sem ele é rejeitado, §8), `summary`, `resultEventId` (M4), `receivedAt` |
| Resultado tardio | `QUOTE#<quoteId>` | `V#<nnnn>#LATE#<alvo>#<queryId>` | `alvo` = `insurerId` ou `ENRICHMENT`; `reason` (`LATE`, `DUPLICATE`), `status`, `queryRef`, `attemptRef`, `rawResponseRef`, resumo da oferta, `receivedAt`; no enriquecimento, também `evidenceId`, `reuseDecision` e `policyVersion`, que rastreiam a evidência de um resultado tardio ou repetido (RN10, T15) |
| Idempotência | `IDEMPOTENCY#<orgId>#<operation>#<scope>#<key>` | `META` | `requestHash`, `resourceRef`, `createdAt` e o que o reenvio devolve: na seleção, `quoteId`, `version`, `selectionId`, `offerId` e `selectedAt`; na reconciliação, `operationId` e `queryId` (M5) |
| Waiter | `WAITER#<workRef>` | `META` | `tokenCiphertext`, `quoteId`, `version`, `kind`, `insurerId`, `executionArn`, `expiresAt`, `status` (`WAITING`, `RESUMING`, `DONE`, `EXPIRED`), `resultEventId`, `resumePayload`, `updatedAt`, `gsi2pk = WAITER_RESUMING` quando `RESUMING`; `gsi2pk = WAITER_EXPIRED`, com `gsi2sk` no instante da expiração, quando `EXPIRED`, até o `reconciler` conferir a entrega (M4) |
| Outbox | `OUTBOX#<eventId>` | `META` | `detailType`, `detail`, `createdAt`, `publishedAt`, `attempts`, `lastError`, `gsi2pk = OUTBOX_PENDING` até publicar, `gsi2sk` = `createdAt` até a primeira falha permanente e, depois dela, o instante da última falha permanente (§7) |
| Evento consumido | `CONSUMED#<eventId>` | `META` | `consumedAt` (descarte de eventos de resultado repetidos) |
| Ocorrência operacional | `ISSUE#<issueId>` | `META` | `issueId`, `organizationId`, `source` (`quote`, `provider`), `issueType` (o tipo da ocorrência: nas da quote, `WORKFLOW_FAILED`, `TASK_EXPIRED`, `STUCK_VERSION` ou `DEAD_LETTER`; o `type` do item é `ISSUE`), `status`, `queryId`, `quoteId`, `version` (nas da quote), `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision`, `gsi1pk = ISSUES#<organizationId>`, `gsi1sk = <lastSeenAt>#<issueId>` (M4). No M5: `projectedAt` nas projetadas da provider; `resolvedAt` na `STUCK_VERSION` resolvida; `gsi2pk = ISSUE_OPEN`, com `gsi2sk` no instante da última observação, só na `STUCK_VERSION` aberta, até ela se resolver (§9) |
| Consulta conhecida | `QUERYREF#<queryId>` | `META` | `queryId`, `organizationId`, `firstSeenAt`; gravado pelo `issue-projector` só a partir de `OperationIssueRaised` e `OperationIssueUpdated` com `queryId` e `organizationId` (§3); autoriza o `RECONCILE` (M5) |
| Reconciliação manual | `OPERATION#<operationId>` | `META` | `operationId`, `organizationId`, `queryId`, `reason`, `requestedBy`, `requestedAt`, `status` (`QUEUED` gravado pela API; depois `SUCCEEDED` ou `FAILED`, do `QueryReconciled`), `safeSummary`, `revision` (0 no `QUEUED`, 2 no resultado), `updatedAt` (M5, §8) |

`<nnnn>` é a versão com quatro dígitos. Todo item tem o atributo `type` (ADR 0015), como `QUOTE`,
`VERSION` ou `OUTBOX`; o filtro do stream depende dele. No `ISSUE#`, o `type` é `ISSUE`, e o tipo da
ocorrência fica em `issueType`, como na provider (§3 e §5). A listagem de ocorrências filtra por
`issueType` e `status` (§11).

Decisão do M5 sobre dados antigos: o Floci local tinha 23 `ISSUE#` (19 `TASK_EXPIRED` e 4
`WORKFLOW_FAILED`) gravados por um build intermediário do M4, com o tipo da ocorrência em `type` e sem
`issueType`, e a API os devolvia com `type: null`. Eles foram migrados à mão no Floci. Não há migração no
código, porque o código commitado sempre gravou no formato atual. Conferir que nenhum `ISSUE#` nesse
formato existe nos ambientes da AWS fica para o M6.

O passo `evaluate-eligibility` grava no `V#n` a decisão (`eligibilityDecision`,
`eligibilityReasonCode`, `eligibilityMissingFields`) com o
`eligibilityRuleVersion`, e o `select-insurers` grava a lista (`selectedInsurers`) com o
`selectionRuleVersion`, cada um quando aplica a regra. Assim a consolidação lê tudo da tabela, sem
depender do estado da execução, e a reconciliação do M4 refaz a consolidação pelo mesmo caminho.
O `issue-projector` grava o `ISSUE#` sem `gsi1pk` quando o evento não traz `organizationId`.

**Ocorrências da quote (M4).** O `reconciler` grava o `ISSUE#` com um `UpdateItem` sem condição: a
mesma situação observada de novo mantém o `firstSeenAt`, atualiza o `lastSeenAt` e o `gsi1sk` e
incrementa o `revision`. O `issueId` é `iss_` + os 32 primeiros caracteres hexadecimais do SHA-256 de
`quote|<issueType>|<alvo>`; o prefixo `quote|` separa essas ocorrências das que a provider gera com o
mesmo tipo e o mesmo alvo. O alvo é `<quoteId>|<version>` em `WORKFLOW_FAILED` e `STUCK_VERSION`, o
`workRef` em `TASK_EXPIRED` e o `eventId` do resultado ou, a partir do M5, do evento da `issues-dlq`
(numa mensagem ilegível, o `messageId` do SQS) em `DEAD_LETTER`. As ocorrências da quote têm
`source = quote`, `reconcilable = false` e o `version` da cotação; a organização vem do `META` da
cotação. O `DEAD_LETTER` da `issues-dlq` é a exceção: a organização, o `queryId` e o `quoteId` vêm do
próprio evento, sem `version` (§9). No M5, cada gravação também volta o `status` a `OPEN` e remove o
`resolvedAt`, e a da `STUCK_VERSION` põe `gsi2pk = ISSUE_OPEN`, com `gsi2sk` no instante da observação:
uma `STUCK_VERSION` resolvida que volta a ser observada reabre. O waiter `EXPIRED` sai do
`WAITER_EXPIRED` na mesma transação que grava a ocorrência, ou sozinho quando não há ocorrência (§8).
Os waiters que a finalização pelo `reconciler` fecha não entram nesse índice: a ocorrência da versão é
o `WORKFLOW_FAILED`.

O `resultEventId` do `RESULT` e da evidência usada é o `eventId` do resultado que a retomada gravou,
o mesmo que ela grava no waiter. Com ele, o `reconciler` reconhece o resultado gravado para um waiter
sem ler o evento de novo.

O `offerId` é determinístico: `of_` + os 32 primeiros caracteres hexadecimais do SHA-256 de
`offer|<workRef>`, então a reentrega do mesmo resultado aponta para a mesma `OFFER`.

O `selectionId` (M5) também é determinístico: `sel_` + os 32 primeiros caracteres hexadecimais do
SHA-256 de `selection|<quoteId>|<version>`. Há uma seleção por versão (RN13), e o `OfferSelected` de uma
versão tem sempre o mesmo `eventId`, mesmo numa corrida de pedidos. O `operationId` da reconciliação
manual é opaco: `op_` + 32 caracteres hexadecimais de um UUID aleatório, sem dado do operador nem da
consulta.

**Resumo do META.** A transação principal de cada passo grava só o `V#n` e os itens filhos. O resumo do
`META` (`phase`, `outcome`, `offerCount`) é atualizado depois, por um `UpdateItem` separado, com
valores absolutos: `SET`, nunca `ADD`; o `offerCount` sai de uma contagem com `ConsistentRead`. A
condição é `currentVersion = :v`, e um `ConditionalCheckFailed` é ignorado, porque indica versão
substituída ou um resumo mais novo já gravado. O M4 só impedia rebaixar o `META` já `TERMINAL`. No M5, o
resumo é monotônico dentro da versão: a condição exige também
`attribute_not_exists(phase) OR phase IN (:fases)`, em que `:fases` são a fase gravada e as anteriores a
ela (`Phase.atOrBefore`), e `attribute_not_exists(offerCount) OR offerCount <= :offerCount`. Assim um
`refreshSummary` com leitura velha do `V#n` não grava `VALIDATING` sobre `QUOTING`, nenhuma fase sobre
`TERMINAL` nem menos ofertas. A versão sem `phase` não grava resumo. A listagem de cotações lê esse resumo
(§11). A criação de versão grava o resumo inicial de `v+1` na mesma transação que muda o
`currentVersion`.

Essa atualização (`QuoteTable.refreshSummary`) é best-effort nos passos do `workflow-steps`, na
`dispatch` e no `resume`. Uma falha de SDK só gera log: o passo já gravado não cai por causa dela. O
`META` pode ficar desatualizado até o `reconciler` do M4 repará-lo, comparando-o com o
`V#currentVersion`. O reparo cobre a versão atual ainda `ACTIVE_VERSION` e também a que já consolidou
(`TERMINAL`) mas cujo `refreshSummary` falhou logo depois. Para essa segunda, a consolidação troca o
`ACTIVE_VERSION` do `V#n` por `gsi2pk = SUMMARY_PENDING`, que o `refreshSummary` bem-sucedido remove;
se ele falha, o `reconciler` encontra a versão no índice, confere o resumo e a tira de lá. O
`reconciler` regrava o resumo só quando ele diverge da versão (`phase`, `outcome` e `offerCount`
contado com `ConsistentRead`), com a mesma condição, inclusive a monotônica do M5.

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
| Consulta | `QUERY#<queryId>` | `META` | `queryKey` (só `ENRICH`), `provider`, `operation` (`ENRICH`, `QUOTE`), `contractVersion`, `organizationId`, `state` (`IN_FLIGHT`, `COMPLETED`, `FAILED`, `UNKNOWN`), `clientReference`, `providerRequestId`, `fencingVersion` (só `ENRICH`), `workRef`, `quoteId`, `version`, `insurerId`, `deadlineAt`, `requestEventId` (só `QUOTE`: o `eventId` do pedido), `gsi2pk = QUERY_IN_FLIGHT` em voo e `QUERY_UNKNOWN` quando `UNKNOWN`, com `gsi2sk` no instante em que ficou `UNKNOWN` e, depois, no da última conferência sem conclusão (M4) |
| Tentativa | `QUERY#<queryId>` | `ATTEMPT#<nnnn>` | `startedAt` (gravado antes do HTTP); `acceptedAt`, `httpStatus` e `providerRequestId` (gravados no `202`); `finishedAt`, `outcome` e `errorClass` (gravados na resposta síncrona ou no callback) |
| Evidência | `QUERY#<queryId>` | `EVIDENCE#<evidenceId>` | `normalizedResult`, `rawResponseRef`, `observedAt`, `receivedAt`, `reusableUntil`, `resultType`, `checksum`, `fencingVersion` (na evidência de quem liderou), `policyVersion` |
| Índice de reuso | `REUSE#<queryKey>` | `META` | `evidenceId`, `queryId`, `reusableUntil`, `resultType` (`FOUND` ou `NOT_FOUND`), `revision`, `policyVersion`, `updatedAt` |
| Liderança | `INFLIGHT#<queryKey>` | `META` | `state` (`IN_FLIGHT`, `COMPLETED`), `fencingVersion`, `firstFencingVersion`, `leaderWorkRef`, `queryId`, `leaseUntil`, `acquiredAt`, `evidenceId`, `resultType` e `completedAt` (só em `COMPLETED`), `ttl` (lápide depois de `COMPLETED`) |
| Seguidor | `INFLIGHT#<queryKey>` | `FOLLOWER#<fencingVersion com 10 dígitos>#<workRef>` | `workRef`, `quoteId`, `version`, `fencingVersion`, `registeredAt`, `ttl` |
| Correlação | `CORR#<provider>#<providerRequestId>` | `META` | `type = CORRELATION`, `provider`, `providerRequestId`, `queryId` (metade do worker, depois do `202` ou, na reconciliação, depois da leitura da situação; §8), `pendingEvents` (metade da API: conjunto de `externalEventId` recebidos só com o `providerRequestId`), `updatedAt` |
| Inbox | `INBOX#<provider>#<externalEventId>` | `META` | `provider`, `externalEventId`, `payload` (corpo recebido), `providerRequestId`, `clientReference`, `receivedAt`, `processingStatus` (`RECEIVED`, `PROCESSED`, `UNMATCHED`), `queryId` e `processedAt` (gravados em `PROCESSED`), `unmatchedAt` (gravado em `UNMATCHED`; M4), `gsi2pk = INBOX_RECEIVED` com `gsi2sk = receivedAt` enquanto `RECEIVED`, e `INBOX_UNMATCHED` quando `UNMATCHED`, com o mesmo `gsi2sk = receivedAt` até a primeira revisão e, depois, o instante da última revisão (M4) |
| Quota | `QUOTA#<provider>#<credentialRef>#<janela>` | `META` | `used`, `ttl` |
| Fan-out pendente | `QUERY#<queryId>` | `FANOUT` | `queryId`, `queryKey`, `leaderWorkRef`, `firstFencingVersion`, `fencingVersion`, `evidenceId`, `concludedAt`, `fannedOutAt` (no fim do fan-out), `gsi2pk = FANOUT_PENDING` com `gsi2sk = concludedAt` até o fan-out terminar (M4) |
| Ocorrência | `ISSUE#<issueId>` | `META` | `issueId`, `issueType` (o tipo da ocorrência; o `type` do item é `ISSUE`), `status`, `target` (alvo do `issueId`), `organizationId`, `queryId`, `quoteId`, `firstSeenAt`, `lastSeenAt`, `safeSummary`, `reconcilable`, `revision`, `updatedAt`, `gsi2pk = ISSUE_OPEN` enquanto aberta, só nos tipos que se resolvem sozinhos, com `gsi2sk = lastSeenAt` até a primeira revisão e, depois, o instante da última revisão (M4) |
| Reconciliação | `OPERATION#<operationId>` | `META` | `operationId`, `organizationId`, `queryId`, `reason`, `requestedBy`, `requestEventId`, `status` (`RUNNING`, `SUCCEEDED`, `FAILED`), `safeSummary`, `revision` (1 no `RUNNING`, 2 no resultado), `createdAt`, `updatedAt` (M4) |
| Outbox | `OUTBOX#<eventId>` | `META` | igual à quote, inclusive o `gsi2sk`; a falha permanente também grava `lastAttemptAt` |
| Evento consumido | `CONSUMED#<eventId>` | `META` | `consumedAt` |

A `queryKey` é o HMAC-SHA256, com o segredo `query-key-secret` como chave, de
`organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef`. Pedidos de cotação
(`operation = QUOTE`) não usam reuso nem liderança.

**Reuso e liderança.** `REUSE#` e `EVIDENCE` gravam o `policyVersion`, a versão da política de reuso em
vigor (parâmetro `reusePolicyVersion`, §12). Um `REUSE#` gravado por outra versão da política não serve
de `HIT`. `NOT_FOUND` declarado pelo provedor entra no índice de reuso; `FAILED` nunca entra. O
`revision` do `REUSE#` cresce a cada troca da evidência apontada, e o `updatedAt` registra a troca.

**Validade da evidência.** O `reusableUntil` do `FOUND` é min(`observedAt`, `receivedAt`) +
`enrichmentValiditySeconds` (§12). A idade da informação conta a partir do `observedAt` (RN05), mas um
`observedAt` no futuro, de um relógio adiantado do provedor, não prolonga a validade além do parâmetro
(RN06). A `EVIDENCE` mantém o `observedAt` declarado pelo provedor; sem ele, ou fora do formato, vale o
`receivedAt`. O `NOT_FOUND` não traz `observedAt` e conta do `receivedAt`. O `REUSE#` e o
`EnrichmentResolved` levam o mesmo `reusableUntil` da evidência.

O `INFLIGHT#` guarda a cadeia corrente da liderança: `firstFencingVersion` é a primeira versão dela, e
`fencingVersion`, a atual. Assumir uma lease vencida continua a cadeia; assumir depois de `COMPLETED`
começa outra, com `firstFencingVersion` igual à nova `fencingVersion` (§8). O `acquiredAt` é o instante
da aquisição, e o `completedAt`, o da conclusão.

O número da versão no `sk` do `FOLLOWER#` tem zeros à esquerda (`FOLLOWER#%010d#<workRef>`), para o
fan-out ler só a faixa de `sk` da cadeia corrente (§8). O `ttl` do `FOLLOWER#` é o mesmo da lápide
(`inflightTombstoneSeconds`). Como o `FOLLOWER#` também tem `quoteId`, uma varredura da tabela por
`quoteId` precisa filtrar por `type`.

O `queryId` é `q_` + os 32 primeiros caracteres hexadecimais do SHA-256 do `workRef` do pedido que cria
a consulta: cada pedido de seguradora e o líder do enriquecimento. A `QUERY` é criada com
`attribute_not_exists`, então a reentrega do mesmo pedido encontra a consulta existente. O limite de
cada `QUOTA` vem da configuração por provedor (§12).

O `evidenceId` é `ev_` + os 32 primeiros caracteres hexadecimais do SHA-256 de
`queryId|<sequência da tentativa>`; a conclusão sem chamada, como por prazo ou quota, usa a sequência 0.
O `attemptRef` do `InsurerResultRecorded` é `<queryId>#<nnnn>`, com a sequência da `ATTEMPT`. Quando
não há resposta do fornecedor, a provider grava mesmo assim uma `EVIDENCE` de falha (`FAILED` no
enriquecimento, `UNAVAILABLE` na seguradora): toda consulta concluída tem evidência, e o
`EnrichmentResolved` sempre leva `evidenceId`.

No M1 não há liderança: a `QUERY` do enriquecimento já grava a `queryKey`, mas não a
`fencingVersion`. O M2 acrescenta `REUSE#` e `INFLIGHT#` sem migração e trata a `fencingVersion`
ausente como consulta sem liderança. No M2, só a `QUERY` de quem liderou grava a `fencingVersion`; a
consulta concluída sem chamada, por prazo ou quota, não a tem e também fica sem liderança.

**Seguradora assíncrona (M3).** A `QUERY` da seguradora guarda o `requestEventId`, o `eventId` do
`InsurerQuoteRequested` que a criou. O resultado de um callback é gravado pela API ou pelo worker sem o
pedido em mãos, e o `CONSUMED` do pedido sai com esse `eventId`; numa consulta sem ele, com o UUIDv5 do
`workRef`, que o contrato atribui ao pedido (§3). A resposta `202` grava na `ATTEMPT` o `acceptedAt`, o
`httpStatus` e o `providerRequestId` e não a encerra: quem grava o `finishedAt` e o `outcome` é o
callback (§8). A `EVIDENCE` do callback usa no `evidenceId` a sequência dessa `ATTEMPT`, tem `observedAt`
e `receivedAt` iguais ao `receivedAt` da inbox e aponta a resposta bruta em
`raw/<queryId>/callback-<externalEventId>.json`; a resposta síncrona fica em
`raw/<queryId>/<nnnn>.json`, e a da API de situação lida pela reconciliação (M4, §9), em
`raw/<queryId>/status-<nnnn>.json`, com a sequência da `ATTEMPT`. Como a chave é determinística,
regravar o objeto na reentrega não deixa órfão.

**Ocorrências e reconciliação (M4).** O `issueId` é `is_` + os 32 primeiros caracteres hexadecimais do
SHA-256 de `<issueType>|<alvo>`, e o alvo fica no atributo `target`:

| Tipo | Alvo |
|---|---|
| `UNKNOWN_QUERY` | `queryId` |
| `UNMATCHED_CALLBACK` | `<provedor>#<externalEventId>` |
| `DEAD_LETTER` | `<fila>\|<chave do pedido>`: o `queryId` derivado do `workRef`, o `operationId`, o `externalEventId` ou, numa mensagem ilegível, o `messageId` do SQS |
| `STALE_OUTBOX` | `eventId` do outbox |

Cada mudança grava o item inteiro com a condição `attribute_not_exists(pk)` ou `revision = :lida`, junto
com o outbox do evento (§3), e incrementa o `revision`. `UNKNOWN_QUERY`, `UNMATCHED_CALLBACK` e
`STALE_OUTBOX` se resolvem sozinhas: enquanto abertas, ficam no índice `ISSUE_OPEN`, que o `reconciler`
confere a cada ciclo para passá-las a `RESOLVED` quando o problema some. `DEAD_LETTER` não entra nesse
índice, porque nenhuma observação posterior traz de volta a mensagem descartada.

No M5, o `queryId` e o `quoteId` de uma ocorrência passaram a ter dono conferido, porque o par
`queryId`/`organizationId` dela autoriza a reconciliação manual na quote (`QUERYREF#`, §4). No
`DEAD_LETTER`, o `reconciler` lê a `QUERY` (`QueryLedger.ownerOf`, que devolve a organização e o
`quoteId` gravados nela) antes de gravar a ocorrência; a regra está no §9.

O `reconciler` lê os índices em ordem de `gsi2sk` e com limite por ciclo. Para nenhum lote ficar preso nos
mesmos itens, o item que continua pendente depois da conferência recebe no `gsi2sk` o instante dela e vai
para o fim do índice: a consulta `UNKNOWN` de seguradora com API de situação que continua `UNKNOWN`, a
ocorrência do `ISSUE_OPEN` que não se resolveu e o callback do `INBOX_UNMATCHED` que segue sem correlação.
A consulta `UNKNOWN` sem API de situação sai do `QUERY_UNKNOWN` depois de abrir `UNKNOWN_QUERY`: nenhuma
leitura a conclui, e a ocorrência fica no `ISSUE_OPEN`, resolvida se a consulta deixar de ser `UNKNOWN`
(§9).

O item `FANOUT` é gravado na transação de conclusão do líder e sai do `FANOUT_PENDING` quando o fan-out
termina. Se o líder cai entre a conclusão e o fan-out, só ele ainda diz quais seguidores esperam a
evidência, e o `reconciler` refaz o fan-out da cadeia gravada nele, mesmo que outra cadeia já tenha
começado (§9).

O `OPERATION#` nasce `RUNNING`, com revisão 1 e `attribute_not_exists`, quando o `reconcileWorker`
recebe o pedido; o resultado (`SUCCEEDED` ou `FAILED`) é a revisão 2, gravada junto com o
`QueryReconciled` no outbox (§8).

**Tabela dos simuladores.** A `provider-solution-<stage>-simulators` tem só `pk`, e o TTL fica no
atributo `ttl`. O uso de cada item está no §10.

| Item | `pk` | Atributos | TTL |
|---|---|---|---|
| Contador do `vehicle-flaky` | `FLAKY#<clientReference>` | `calls` | 1 h |
| Situação da beta | `BETA#<providerRequestId>` | `providerRequestId`, `clientReference`, `scenario`, `receivedAt`, `readyAt`, `result` | 1 dia |
| Auxiliar da beta | `BETAREF#<clientReference>` | `providerRequestId` | 1 dia |

## 6. Máquina de estado principal

Entrada: `{"quoteId": "...", "version": 1}`. Nome da execução: `q-<quoteId>-v<version>`.
`TimeoutSeconds` da máquina: `quoteDeadlineSeconds` + `workflowMarginSeconds` (parâmetro
`workflowTimeoutSeconds`, 360 no local; §12). Ele é
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
   existe, devolve `{resultType: FAILED, errorClass}`, com o nome do erro capturado (`Error`, sem a
   `Cause`), e marca o waiter `EXPIRED` (regra abaixo). Só `FOUND` segue; `NOT_FOUND` e `FAILED` vão
   para `ConsolidateQuote`.
4. `SelectInsurers` (`step = select-insurers`): confere por conta própria a evidência usada do
   enriquecimento (§4) e, se ela não é `FOUND`, devolve a lista vazia, sem gravar. Com `FOUND`, aplica a
   `sel-1` (`InsurerSelectionRule.select`) ao perfil enriquecido que a evidência usada guarda: o uso vem
   do snapshot, e o ano do veículo, do `vehicle.year` do perfil, com o ano do snapshot como reserva
   (§10). Devolve `$.selection.insurers`, limitada a `maxInsurers`, e grava no `V#n` o
   `selectedInsurers` e o `selectionRuleVersion`. Com lista não vazia, a mesma transação grava a fase
   `QUOTING` e um `RESULT` `PENDING`, com `insurerName`, para cada seguradora. Com lista vazia (T26), grava
   `selectedInsurers = []` e o `selectionRuleVersion`, e a fase fica `ENRICHING` até a consolidação, que
   termina `NO_OFFER` com `NO_APPLICABLE_INSURER`. Lista vazia vai para `ConsolidateQuote`.
5. `QuoteInsurers`: `Map` com `MaxConcurrency = mapConcurrency` (2) e
   `ItemSelector = {quoteId, version, insurerId, executionArn}`. Cada item chama a `dispatch` com
   `kind = INSURER` e `TimeoutSeconds = insurerTimeoutSeconds`. `States.Timeout` chama
   `RecordInsurerOutcome` (`step = record-insurer-outcome`) com `TIMED_OUT` e `reasonCode =
   STEP_TIMEOUT`, e `States.ALL` com `UNAVAILABLE` e `reasonCode = TASK_FAILED`. Ele marca o waiter
   `EXPIRED` (regra abaixo) e grava o `RESULT` só se ainda não for terminal. `ResultPath: null`. Com as
   duas seguradoras do laboratório, as duas esperas correm juntas, e o orçamento conta
   ceil(n / `mapConcurrency`) esperas (§9). Cada item grava o próprio `RESULT` e a própria oferta na
   retomada (§8), então a oferta de uma seguradora aparece no GET antes de a outra terminar (RF08, T08).
6. `ConsolidateQuote` (`step = consolidate`): aplica a precedência do PRD (§8) com atualização
   condicional pela `revision` do `V#n`, grava `TERMINAL`, `outcome`, `outcomeReason` e o outbox
   `QuoteCompleted`. Com duas seguradoras, os 15 pares de resultados terminais seguem a precedência nas
   duas ordens (`ConsolidationTest`). Em `NEEDS_INFORMATION` sem oferta, o `missingFields` da versão é a
   união, por `path`, dos campos pedidos pelas seguradoras, com `INSURER_NEEDS_INFORMATION`. Em
   `PARTIAL`, o `missingFields` da versão fica vazio, mesmo com `NEEDS_INFORMATION` de uma seguradora:
   os campos pedidos ficam no `RESULT` dela (`insurerResults[].missingFields`). O PRD só exige que os
   motivos de cada seguradora continuem disponíveis, qualquer que seja o `outcome`.

Os dois `record-*-outcome` marcam o waiter `EXPIRED` com a condição
`attribute_not_exists(pk) OR status = WAITING`: se a `dispatch` nunca gravou o waiter (a invocação
falhou antes), eles o criam já `EXPIRED`. Assim a consolidação distingue uma etapa que falhou de uma
que nunca rodou, e um resultado que chegue depois vira evidência tardia (§8).

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
| `resume` | Fila `results` | Grava resultado, retoma com `SendTaskSuccess`; falha que não vem de condição volta em `batchItemFailures` (§8) |
| `outbox-relay` | Stream da tabela | Publica eventos, inicia execuções e grava o `executionArn` |
| `issue-projector` | Fila `issues` | Projeta ocorrências no `ISSUE#` e no `QUERYREF#` e reconciliações no `OPERATION#`, com o `CONSUMED#` na mesma transação; falha volta em `batchItemFailures` (M5, §3) |
| `reconciler` | Agendamento (1 min) | M1: republica `OUTBOX_PENDING`. M4: waiters, versões presas, execuções encerradas, resumo do `META`, ocorrências da quote e DLQs (§9). M5: ciclo de vida das ocorrências da quote (`ISSUE_OPEN`) e `issues-dlq` |

No Floci, cada função Java vira um container, e a memória da máquina de desenvolvimento é limitada.
Por isso os passos simples ficam numa só função, `workflow-steps`. O papel IAM dela é a união dos
passos e só toca a tabela da quote. `dispatch`, `resume`, `outbox-relay`, `issue-projector` e
`reconciler` continuam separados, porque têm gatilho ou permissões próprios: `states:SendTaskSuccess`
só na `dispatch`, no `resume` e no `reconciler`; `states:StartExecution` na relay e no `reconciler`,
que republica também o `StartQuoteRequested` com o mesmo código de publicação da relay;
`states:DescribeExecution` só no `reconciler`.

No M4, o papel do `reconciler` ganhou `PutItem` e `Query` na tabela (a finalização grava o outbox
`QuoteCompleted` e lê os itens da versão), `states:SendTaskSuccess` e a leitura do segredo `token-key`,
para retomar o waiter `RESUMING` com o token decifrado (T13), `states:GetExecutionHistory`, que confere a
entrega antes do `TASK_EXPIRED` (§8), e, no SQS, `ReceiveMessage`,
`DeleteMessage` e `GetQueueAttributes` na `results-dlq` e `SendMessage` na `results` (§9). O papel da
API ganhou `UpdateItem`, que a nova versão usa no `META` e na versão substituída (§8). As variáveis do
`reconciler` (`RESULTS_QUEUE_URL`, `RESULTS_DLQ_URL`, `DLQ_MAX_ATTEMPTS`,
`STUCK_VERSION_MARGIN_SECONDS` e `RESUMING_RETRY_AFTER_SECONDS`) ficam no `environment` do `provider`
do `serverless.yml`, e não no da função: o zip das functions valida a configuração ao subir, em
qualquer função.

No M5:

- o `issue-projector` tem papel próprio, o `IssueProjectorRole`: `GetItem`, `PutItem` e `UpdateItem` na
  tabela (o `GetItem` lê a organização do `OPERATION#` antes de projetar um `QueryReconciled`, §3) e
  `ReceiveMessage`, `DeleteMessage` e `GetQueueAttributes` na fila `issues`. O
  `FunctionTimingConfigurationTest` confere a lista exata. A função usa o zip das functions, com
  `QUARKUS_LAMBDA_HANDLER = issue-projector`, timeout `issueProjectorTimeoutSeconds` e o ESM da fila
  com `batchSize: 1` e `ReportBatchItemFailures`;
- o papel da API ganhou `ConditionCheckItem` na tabela, que a seleção usa para conferir a oferta na
  transação (§8), e `Query` no índice `gsi1`, das listagens de cotações e de ocorrências (§11);
- o papel do `reconciler` ganhou `ReceiveMessage`, `DeleteMessage` e `GetQueueAttributes` na
  `issues-dlq` e `SendMessage` na `issues`, para reprocessar a DLQ (§9); a revisão das ocorrências usa a
  leitura do `gsi2` e o `UpdateItem` que ele já tinha. As variáveis `ISSUES_QUEUE_URL` e `ISSUES_DLQ_URL`
  ficam, como as outras do `reconciler`, no `environment` do `provider`.

O `outbox-relay` e o `resume` não usam os modelos do `aws-lambda-java-events`: leem o JSON cru do
evento de stream ou de SQS e convertem o `NewImage` direto para o `AttributeValue` do SDK v2. O Jackson
do Quarkus não mapeia `Records` e `NewImage` desses modelos sem mixins.

### provider-solution

| Função | Gatilho | Papel |
|---|---|---|
| `api` (`provider-api`) | API Gateway `POST /v1/providers/{provider}/callbacks` | Assinatura, inbox, encontro no `CORR#` e resultado dos callbacks (§8) |
| `simulators` (`provider-simulators`) | API Gateway `/sim/{proxy+}` | Fornecedores simulados |
| `simCallbackSender` (`provider-simulators`) | Fila `sim-callbacks` | Assina e envia à `api` os callbacks agendados pela beta (§10) |
| `enrichmentWorker` | Fila `enrichment-requests` | Reuso, liderança, quota, chamada ao provedor |
| `insurerWorker` | Fila `insurer-requests` | Quota, chamada à seguradora, `202` e metade do worker no `CORR#` (§8) |
| `reconcileWorker` | Fila `reconcile-requests` | Reconciliação manual (M4): `OPERATION#`, o mesmo caminho de reconciliação do `reconciler` e `QueryReconciled` (§8) |
| `outboxRelay` | Stream da tabela | Publica eventos |
| `reconciler` | Agendamento (1 min) | M1: republica `OUTBOX_PENDING`. M4: fan-out pendente, consultas em voo além do prazo e `UNKNOWN`, inbox parada ou sem correlação, DLQs, ocorrências e métricas (§9) |

Cada módulo tem a sua forma de entrada. Os workers, a `outboxRelay` e o `reconciler` são do
`provider-functions` e usam o `FunctionInvoker` do Spring Cloud Function, com a função escolhida por
`SPRING_CLOUD_FUNCTION_DEFINITION`. A `api` e a `simulators` são Spring MVC atrás do
`serverless-java-container` (`StreamLambdaHandler`), com o contexto criado uma vez por container. O
`simCallbackSender` tem handler próprio (`SimCallbackSenderHandler`), sem Spring: lê o evento SQS como
JSON cru, como o `resume` da quote, e devolve `batchItemFailures`. O motivo está no §1.

O `provider-functions` lê `SQSEvent` e `DynamodbEvent` com `com.amazonaws:aws-lambda-java-events` e
`aws-lambda-java-serialization`, os dois no escopo `compile`. A desserialização desses eventos foi
verificada no Floci (§13). A configuração leva
`logging.level.org.springframework.cloud.function.adapter.aws=WARN`.

### Relay de outbox (as duas soluções)

O evento `stream` do `outbox-relay` e do `outboxRelay` declara:

- `batchSize: 100`;
- `filterPatterns` com `eventName: [INSERT]` e `dynamodb.NewImage.type.S: [OUTBOX]`;
- `maximumRetryAttempts: 3` e `bisectBatchOnFunctionError: true`;
- `functionResponseType: ReportBatchItemFailures`;
- `destinations.onFailure` para a fila `stream-failures`, que tem alarme.

A relay trata cada registro separadamente. Ela lê o `NewImage` como `AttributeValue` do SDK v2: na
quote, direto do JSON do evento; na provider, convertendo o do modelo de eventos.
`ExecutionAlreadyExists` com a mesma entrada conta como sucesso. O
`FailedEntryCount` do `PutEvents` é conferido por entrada. Erro transitório vira falha do item em
`batchItemFailures`. Erro permanente grava `attempts` e `lastError` no `OUTBOX` e não derruba o lote; o
item continua `OUTBOX_PENDING`.

O `gsi2sk` do `OUTBOX_PENDING` nasce com o `createdAt`. A falha permanente o troca pelo instante da
falha, e cada nova falha permanente o atualiza de novo. O republicador lê os pendentes mais antigos pelo
`gsi2sk`, então um item que sempre falha vai para o fim da fila e não prende os demais. Na quote, a
gravação da falha exige `attribute_exists(gsi2pk)`, para não tocar um item já publicado. A ocorrência
`STALE_OUTBOX` do M4 mede a idade pelo `createdAt`, não pelo `gsi2sk`.

Com `ReportBatchItemFailures`, o stream reentrega o lote a partir do registro que falhou, inclusive os
seguintes, e a divisão do lote reentrega registros vizinhos mais de uma vez (§13). Por isso a
publicação repetida não pode ter efeito: o `eventId` vai no `detail` e os consumidores descartam a
repetição (§3), `ExecutionAlreadyExists` conta como sucesso e o `publishedAt` é gravado com `SET`. A mensagem da `stream-failures` só aponta o shard e a faixa de
sequência, sem o registro: ela serve ao alarme. Um registro descartado do stream só volta pelo
republicador, que é o `reconciler` desde o M1 e lê o `OUTBOX_PENDING` na tabela, não no stream, que
guarda os registros por 24 h. O `reconciler` das duas soluções registra no log o `eventId`, o
`detailType` e o `createdAt` de cada item que republica, para uma republicação ser identificada sem ler
a tabela.

Na quote, depois do `StartExecution`, a relay grava o `executionArn` no `V#n`, inclusive quando o
`StartExecution` repetido devolve a execução existente. O ARN também é derivável do nome da máquina e
da execução.

As chamadas a fornecedores usam HTTP. A URL base vem de `PROVIDERS_BASE_URL`, montada no
`serverless.yml` com `Fn::Sub` a partir do `ApiGatewayRestApi` da própria stack: no stage `local`,
`http://floci:4566/restapis/${ApiGatewayRestApi}/local/_user_request_`; nos demais,
`https://${ApiGatewayRestApi}.execute-api.${aws:region}.amazonaws.com/${sls:stage}`. O Serverless
resolve `${aws:region}` e `${sls:stage}` antes do deploy, e só `${ApiGatewayRestApi}` chega ao
`Fn::Sub`; `${AWS::Region}` conflitaria com a sintaxe de variáveis do Serverless. O caminho
`_user_request_` chamado de dentro de uma Lambda funciona no Floci (§13). O `simCallbackSender` chega à
`api` pelo mesmo endereço, em `CALLBACKS_BASE_URL`, montado do mesmo parâmetro.

## 8. Fluxos

**Criação.** O `POST /v1/quotes` resolve as referências no catálogo da organização e grava, numa
transação: idempotência, `META` (com o resumo inicial), `V#0001` (`phase = RECEIVED`), outbox interno
`StartQuoteRequested` e outbox `QuoteAccepted`. O `outbox-relay` inicia a execução com o nome
determinístico e grava o `executionArn` no `V#0001`; `ExecutionAlreadyExists` com a mesma entrada
conta como sucesso.

O `POST /v1/quotes` rejeita campos desconhecidos com 400, inclusive dentro de `risk`: o `requestHash`
é calculado sobre o body, e ele não pode depender de um campo que a API ignoraria. Pelo mesmo motivo,
JSON com conteúdo depois do primeiro valor, como `{...} lixo`, responde 400. O `202` traz no
`Location` o caminho relativo, igual ao `statusUrl`. O header é montado à mão, porque o
`Response.location(URI)` do JAX-RS resolve o caminho contra a URI base da aplicação e, atrás do API
Gateway, produz uma URL absoluta sem o stage.

**Conflitos de idempotência.** O `TransactionConflict` é repetido no código, com backoff e jitter, em
até 4 tentativas. Uma transação que continua cancelada, por qualquer motivo, é decidida pelo registro
de idempotência, lido com `GetItem` e `ConsistentRead` (ou `ReturnValuesOnConditionCheckFailure =
ALL_OLD`, se o Floci suportar; §13):

- Registro presente: compara o `requestHash`. Mesmo hash devolve a mesma identidade; hash diferente,
  409 `IDEMPOTENCY_CONFLICT`.
- Registro ausente: a transação não gravou nada, e nenhuma outra criação com a chave foi aceita. A API
  responde 503 `SERVICE_UNAVAILABLE`.

Toda falha antes da aceitação durável responde 503 `SERVICE_UNAVAILABLE`, com a orientação de reenviar
com a mesma `Idempotency-Key`. Além da transação cancelada sem registro de idempotência, isso cobre o
timeout do SDK e o throttling ou 5xx do DynamoDB. Se a gravação aconteceu apesar do erro, o reenvio
relê o registro e devolve a mesma cotação. Só um 4xx de serviço que não seja throttling continua 500.

O mesmo tratamento vale para `CREATE_VERSION`, `SELECT_OFFER` e `RECONCILE`.

**Nova versão** (`POST /v1/quotes/{id}/versions`). Cotação de outra organização responde 404 antes da
transação. Uma `TransactWriteItems` grava:

1. Put do `IDEMPOTENCY` (`attribute_not_exists`).
2. Update do `META`: `SET currentVersion = :n` e o resumo inicial (`phase = RECEIVED`, `outcome`
   nulo, `offerCount = 0`), com a condição
   `currentVersion = :expected AND attribute_not_exists(selectionId) AND organizationId = :org`.
3. Update do `V#<expected>`: `SET supersededAt` e `updatedAt` e incremento do `revision`.
4. Put do `V#<n>` (`attribute_not_exists`), com `n = expected + 1`.
5. Outbox `StartQuoteRequested` e `QuoteAccepted` da nova versão.

Uma transação cancelada é decidida nesta ordem. Primeiro o registro de idempotência, antes do `META`:
presente, devolve a mesma versão ou responde 409 `IDEMPOTENCY_CONFLICT`, como na criação. Sem ele, se a
condição que falhou é a do `META` (a 2ª ação), a API relê o `META`: com `selectionId`, responde 409
`QUOTE_ALREADY_SELECTED`; sem ele, 409 `VERSION_CONFLICT`. Qualquer outro cancelamento responde 503
`SERVICE_UNAVAILABLE`. A execução da versão antiga segue até o fim, sem `StopExecution`. Os resultados
dela, inclusive os que chegam depois do `createdAt` da nova versão, ficam nela. A versão substituída
fica sem `allowedActions`, e as ofertas dela, com `unavailabilityReason = VERSION_SUPERSEDED` (T17).

**Seleção** (`POST /v1/quotes/{id}/versions/{v}/selection`, M5). Registra a intenção de proposta
(RN13), sem emitir proposta, e é permitida também durante `QUOTING`. A API exige `offers:select`, lê o
`META` (cotação de outra organização ou inexistente responde 404) e só depois confere a
`Idempotency-Key` e o body `{"offerId": "..."}`: campo desconhecido, `offerId` ausente, vazio ou acima de
128 caracteres responde 400 `VALIDATION_ERROR`. O registro de idempotência já gravado decide primeiro: o
mesmo hash devolve a seleção gravada, e outro hash responde 409 `IDEMPOTENCY_CONFLICT`. Sem registro, a
decisão sai do `META` e da `OFFER` lidos agora (`Selections.decide`, na `quote-domain`), nesta ordem:

| Situação | Resposta |
|---|---|
| Cotação de outra organização, versão maior que `currentVersion` ou `offerId` que não está na versão | 404, sem revelar o motivo |
| Seleção existente na mesma versão com a mesma `offerId` | 201 com a seleção existente, mesmo com chave nova e mesmo depois de a oferta expirar (T28) |
| Seleção existente, pedido numa versão que não é a atual | 409 `VERSION_SUPERSEDED` |
| Seleção existente com outra `offerId` | 409 `SELECTION_ALREADY_EXISTS` (T28) |
| `v` diferente de `currentVersion`, sem seleção | 409 `VERSION_SUPERSEDED` |
| `validUntil` igual ou anterior ao relógio do servidor | 409 `OFFER_EXPIRED` (T18) |
| Oferta válida da versão atual, sem seleção | Grava, abaixo, e responde 201 |

Em nenhuma recusa algo é gravado: nem `SELECTION`, nem `selectionId` no `META`, nem idempotência, nem
`OfferSelected` (T18). Para gravar, uma `TransactWriteItems`, com o `TransactionConflict` repetido como
nas outras mutações, faz:

1. Put do `IDEMPOTENCY` (`attribute_not_exists`).
2. Put do `V#<v>#SELECTION` (`attribute_not_exists`).
3. Update do `META`: `SET selectionId, selectedVersion, selectedOfferId, updatedAt`, com a condição
   `currentVersion = :v AND attribute_not_exists(selectionId) AND organizationId = :org`, a mesma da
   nova versão.
4. `ConditionCheck` na `OFFER`: `attribute_exists(pk) AND validUntil > :agora`.
5. Outbox `OfferSelected`, com `eventId` UUIDv5 do `selectionId` (§4).

A transação cancelada relê primeiro o registro de idempotência, como na criação. Sem ele, se falhou a
condição da `SELECTION` ou a do `META` (outra seleção ou uma nova versão venceu a corrida), a API relê o
`META` e decide de novo pela tabela acima; se falhou a da `OFFER`, responde 409 `OFFER_EXPIRED` (a oferta
expirou entre a leitura e a gravação). Qualquer outro cancelamento, e a falha da AWS antes da aceitação
durável, responde 503 `SERVICE_UNAVAILABLE`. O 201 traz `selectionId`, `offerId`, `selectedAt` e
`status = INTENT_RECORDED`, e o GET da versão passa a mostrar a mesma `selection`; o `updatedAt` da
versão considera também o `selectedAt`.

Seleção e nova versão escrevem o mesmo `META`, com a mesma condição. Por isso o DynamoDB deixa só uma das
duas passar (T29): com a seleção vencedora, as novas versões recebem 409 `QUOTE_ALREADY_SELECTED`; com a
nova versão vencedora, as seleções recebem 409 `VERSION_SUPERSEDED`, e as outras novas versões, 409
`VERSION_CONFLICT`. No Floci, a corrida não produz `TransactionConflict`, porque ele serializa as
transações condicionais (§13).

**Ações permitidas.** O GET deriva os campos abaixo; `serverTime` é o relógio do servidor na leitura.

| Campo | Regra |
|---|---|
| `CREATE_VERSION` | `v = currentVersion`, sem `selectionId` no `META` e permissão `quotes:write` |
| `SELECT_OFFER` | `v = currentVersion`, sem `selectionId`, permissão `offers:select` e ao menos uma oferta `selectable` |
| `selectable` (oferta) | `v = currentVersion`, sem `selectionId` e `validUntil > serverTime` |
| `unavailabilityReason` (oferta) | `VERSION_SUPERSEDED`, `SELECTION_ALREADY_EXISTS` ou `OFFER_EXPIRED`, nessa ordem de precedência; nulo quando `selectable` |
| `RECONCILE` (ocorrência) | `queryId` presente, `reconcilable = true`, `status = OPEN` (M5) e permissão `operations:reconcile` (`Operations.issueActions`) |

`unavailabilityReason` é um enum fechado no OpenAPI.

Nenhuma dessas regras depende da fase. Durante `QUOTING`, a oferta que uma seguradora já gravou (a da
alpha, enquanto a beta espera o callback) é `selectable`, e `SELECT_OFFER` aparece em `allowedActions`.
É o que o PRD pede: a seleção é permitida também durante `QUOTING`, com a oferta válida e a versão
atual, e as outras consultas continuam (PRD, "Seleção e concorrência"). O endpoint de seleção entrou no
M5 (acima). Depois da seleção, a versão fica sem `allowedActions`, mesmo que uma seguradora responda
depois com `NEEDS_INFORMATION`: a nova versão exigiria `CREATE_VERSION`, e ela é recusada com 409
`QUOTE_ALREADY_SELECTED` (T29). O `RECONCILE` exige a ocorrência aberta porque a resolvida não tem mais o
que reconciliar.

**Listagem de ofertas.** O `GET /v1/quotes/{id}/versions/{v}/offers` pagina com `cursor` e `limit`. O
`limit` vai de 1 a 100; ausente ou vazio (`limit=`), vale 20. Valor não numérico ou fora da faixa
responde 400 `VALIDATION_ERROR`, com `fieldErrors[path=limit]`. No M5, a mesma regra de `limit`
(`Paging`) vale para a listagem de cotações, a de ocorrências e o catálogo; os cursores e os filtros de
cada uma estão no §11.

**Pedido.** A `dispatch` recebe `kind`, `insurerId` e o `executionArn`. Ela cifra o token com
AES-256-GCM, usando o `workRef` como dado associado. A chave é o HMAC-SHA256 do segredo `token-key`
sobre o rótulo `quote-solution/task-token/v1`. O `eventId` do pedido é o UUIDv5 do `workRef`, sem o
token. No `InsurerQuoteRequested`, o `risk.coverageProfile` vai resolvido no catálogo, como objeto
(§3). A `dispatch` é idempotente:

- Na primeira vez, uma transação faz Put do waiter `WAITING` e do outbox do pedido, os dois com
  `attribute_not_exists`. Na seguradora, a mesma transação passa o `RESULT` de `PENDING` para
  `PROCESSING`, com escrita condicional.
- Nas seguintes (o `Retry` da tarefa gera token novo), só um Update do `tokenCiphertext`, com a
  condição `status = WAITING`, sem outbox novo.
- Se o waiter já tiver `resumePayload` (`RESUMING`, `DONE` ou `EXPIRED`), a `dispatch` chama
  `SendTaskSuccess` com o token novo e esse `resumePayload`.

A `dispatch` não grava o `V#n`: as fases são dos passos do `workflow-steps` (§6). Na seguradora,
depois da transação, ela atualiza o resumo do `META`, em best-effort (§4).

**Retomada.** O `resume` lê o evento da fila `results` e o waiter do `workRef`. No enriquecimento, ele
confere o contrato do `EnrichmentResolved` antes de tudo (abaixo). Se agora ≥ `expiresAt` do waiter (o
`deadlineAt` do pedido), grava `LATE` com o `CONSUMED` e para; como o prazo é imutável, a verificação
fica no código. Nos demais casos, faz uma única transação com:

- Update do waiter de `WAITING` para `RESUMING`, com `resultEventId` e `resumePayload` (condição
  `status = WAITING`);
- na seguradora, `RESULT` e `OFFER` (condição: resultado ainda não terminal); no enriquecimento, a
  evidência usada `V#n#EVIDENCE#<workRef>` com `attribute_not_exists`, que impede duas evidências para
  o mesmo pedido;
- o `CONSUMED#<eventId>`.

Se a transação for cancelada por `ConditionalCheckFailed`, o `resume` relê o waiter. `RESUMING` com o
mesmo `resultEventId` segue para o `SendTaskSuccess`: é a retomada refeita na reentrega. Fora desse
caso, se o `CONSUMED#<eventId>` já existe, o evento é descartado: nada é gravado, e o `resume` registra
no log `result <eventId> already consumed, discarded`. Nos demais casos, grava o resultado
tardio (§4) com o `CONSUMED`: `DUPLICATE` quando o waiter já retomou com outro resultado, `LATE` quando
expirou ou o resultado já é terminal. Depois do `SendTaskSuccess`, marca `DONE` com a condição
`status = RESUMING`. `TaskTimedOut` ou `InvalidToken` marcam `EXPIRED`, também só com
`status = RESUMING`, para nunca rebaixar um `DONE`. Por fim, atualiza o resumo do `META`, em
best-effort (§4).

Um caso de borda: se o `resume` cai depois de um `SendTaskSuccess` aceito e antes de marcar `DONE`, a
reentrega encontra o waiter `RESUMING` com o mesmo `resultEventId` e chama `SendTaskSuccess` de novo. A
tarefa já terminou, a resposta é `TaskTimedOut` ou `InvalidToken`, e o waiter fica `EXPIRED`, embora o
resultado tenha sido entregue. Por isso o `status` sozinho não decide o `TASK_EXPIRED` do M4 (§3).

Só o `ConditionalCheckFailed` leva a `DUPLICATE` ou `LATE`. Os outros motivos de cancelamento, como
throttling, `ProvisionedThroughputExceeded` e o `TransactionConflict` que sobra depois das 4
tentativas, não dizem nada sobre o estado gravado: o `resume` relança o erro, e a mensagem volta em
`batchItemFailures`. A gravação do resultado tardio segue a mesma regra: só o `ConditionalCheckFailed`
do `CONSUMED` descarta o evento, com a mesma linha de log. Como o descarte não grava nada, nos dois
caminhos essa linha é o único rastro dele, e o T11 do `quote-e2e` a confere (§10).

**Tarefa expirada (M4).** Como o caso de borda acima mostra, o `status` não basta. O waiter que fica
`EXPIRED`, pela retomada ou pelos `record-*-outcome` (§6), entra no índice
`gsi2pk = WAITER_EXPIRED` (§4), e o `reconciler` o confere. Ele só abre `TASK_EXPIRED` quando o waiter
expirou com um resultado gravado para ele (o `resumePayload`, ou o `resultEventId` no `RESULT` da
seguradora ou na evidência usada do enriquecimento) e o `GetExecutionHistory` da execução encerrada não
mostra a entrega. Conta como entrega um `TaskSucceeded` cuja saída é o `resumePayload` levado pelo
`SendTaskSuccess`, ou cujo `Payload`, a saída do passo de timeout (`record-*-outcome`, §6), traz o
resultado gravado que esse passo usou. Na seguradora, o passo devolve só a seguradora e a situação, e a
comparação usa esses dois campos. Enquanto a execução roda, o waiter fica no índice para o ciclo seguinte. Sem
`executionArn`, ou com a execução apagada, a entrega não tem prova, e a ocorrência é aberta. O waiter
que expirou sem resultado é o timeout normal da etapa, já visível ao usuário no `RESULT` `TIMED_OUT`
com `STEP_TIMEOUT`: ele só sai do índice, sem ocorrência. Conferido, o waiter sai do índice, na mesma
transação da ocorrência, quando há.

**Reentrega depois do prazo.** A reentrega da fila `results` vem depois da visibilidade de 90 s (91 s
medidos no Floci, §12), já depois do `expiresAt` das esperas de 20 s do stage `local`. Ela toma o
caminho do prazo: grava `LATE` ou é descartada pelo `CONSUMED`, sem refazer o `SendTaskSuccess`. A
retomada refeita na reentrega só existe antes do prazo, então, no local, quem retoma um waiter
`RESUMING` é sempre o `reconciler` (§9). O resultado não se perde, porque já foi gravado na transação
da retomada, e os `record-*-outcome` usam o que está gravado: na seguradora, o `RESULT` terminal não é
sobrescrito; no enriquecimento, o `resumePayload` é lido (§6).

No enriquecimento, a evidência usada tem o mesmo formato em `HIT`, `JOIN` e `MISS`, com o
`evidenceId`, o `queryId`, o `reuseDecision` e o `policyVersion` do evento. O `resume` confere o
`EnrichmentResolved` contra o AsyncAPI da provider em toda entrega, antes do ramo de prazo:

- `queryId`, `evidenceId`, `policyVersion` e `reuseDecision` obrigatórios, e os três primeiros como
  texto nos patterns da provider: `^q_[0-9a-f]{32}$`, `^ev_[0-9a-f]{32}$` e
  `^[A-Za-z0-9][A-Za-z0-9._-]*$`;
- `FOUND` com `result`;
- `notFoundRefs`, quando presente, como lista de textos declaráveis (`vehicleRef` e
  `principalDriverRef`), e não vazio no `NOT_FOUND`;
- nenhum `HIT` com `resultType` `FAILED`, porque `FAILED` nunca entra no índice de reuso (§5).

Um evento fora do contrato não é gravado. O `resume` relança o erro, a mensagem volta à fila em
`batchItemFailures` e, depois do `maxReceiveCount`, vai para a DLQ. A conferência vem antes do prazo
porque a reentrega chega depois da visibilidade de 90 s, já depois do `expiresAt`: conferido só no
caminho do prazo, o evento fora do contrato viraria evidência tardia (`LATE` ou `DUPLICATE`) e nunca
chegaria à DLQ. A versão termina `TECHNICAL_FAILURE` com `ENRICHMENT_FAILED` pelo timeout da espera
(§6, passo 3), sem inventar dado (RN07).

**Enriquecimento.** O `enrichmentWorker` calcula a `queryKey` e decide nesta ordem, com leituras em
`ConsistentRead`:

1. Reentrega: a própria `QUERY`, depois o `CONSUMED` do pedido, depois o registro como seguidor na
   cadeia corrente (abaixo).
2. Prazo: com o `deadlineAt` vencido, conclui sem chamada (§9), com consulta própria e sem liderança, e
   publica `MISS`, mesmo com evidência válida no índice.
3. `HIT`: o `REUSE#` aponta evidência reutilizável, ainda válida e gravada pela versão da política
   de reuso em vigor (`policyVersion`, §5). Uma transação grava o outbox `EnrichmentResolved` e o
   `CONSUMED` do pedido.
4. `JOIN`: há liderança `IN_FLIGHT` com lease válida. Uma transação faz `ConditionCheck` no `INFLIGHT`
   (`state = IN_FLIGHT AND fencingVersion = :v`) e Put do `FOLLOWER#<v>#<workRef>`, com
   `attribute_not_exists`.
5. Liderança: não há `INFLIGHT`, ou ele está `COMPLETED` sem evidência válida no índice, ou a lease
   venceu. Uma transação, antes da chamada, grava:
   - Update do `INFLIGHT`: `state = IN_FLIGHT`, `fencingVersion` incrementada (1 sem item),
     `firstFencingVersion`, `leaderWorkRef`, `queryId`, `leaseUntil` (agora +
     `enrichmentLeaseSeconds`) e `acquiredAt`; remove `evidenceId`, `resultType`, `completedAt` e o
     `ttl` da lápide. A condição fixa a versão lida: `attribute_not_exists(pk)` sem item e, com item,
     `fencingVersion = :lida AND (state = COMPLETED OR leaseUntil < :agora)`;
   - `ConditionCheck` no `REUSE#` com a revisão lida (`revision = :lida`) ou, se ele não existia,
     `attribute_not_exists(pk)`;
   - Put da `QUERY` (`IN_FLIGHT`, com a `fencingVersion`, `attribute_not_exists`) e da `ATTEMPT#0001`
     com `startedAt`;
   - débito da quota (§9).

A condição do `INFLIGHT` é mais estrita que `state = COMPLETED OR leaseUntil < :agora`, e o
`ConditionCheck` no `REUSE#` completa a proteção: sem eles, um pedido que lê o índice antes da
conclusão de outro líder e tenta a liderança depois dela repetiria a chamada externa. Se a transação
da liderança for cancelada pela condição do `INFLIGHT` ou do `REUSE#`, o worker relê os dois e decide
de novo, em até 5 rodadas; depois disso, a mensagem volta à fila. Sem quota na reserva da liderança,
segue o §9; a conclusão sem chamada por quota também publica `MISS`, sem liderança.

O líder chama o provedor sem transação aberta, com `clientReference = queryId`. A conclusão é uma
transação com:

- `QUERY` concluída e `ATTEMPT` com `finishedAt`;
- Update do `INFLIGHT` para `COMPLETED`, com `evidenceId`, `resultType`, `completedAt` e `ttl`
  (`inflightTombstoneSeconds`), com a condição
  `fencingVersion = :minha AND queryId = :minha AND state = IN_FLIGHT`; o item fica como lápide, em vez
  de ser apagado;
- Put do `REUSE#`, só se o resultado for reutilizável (`FOUND` ou `NOT_FOUND` declarado; `FAILED`
  nunca), com `revision` incrementada e a condição pela revisão lida logo antes:
  `attribute_not_exists(pk)` se o índice não existia, `revision = :lida` se existia. Se só o índice
  mudou entre a leitura e a transação, o líder relê a revisão e tenta de novo, em até 3 rodadas;
- `EVIDENCE` imutável, com `fencingVersion` e `policyVersion`;
- outbox próprio (`MISS`) e `CONSUMED` do pedido.

A condição do `INFLIGHT` confere também o `queryId`, porque a `fencingVersion` sozinha não identifica a
liderança: depois que o TTL apaga a lápide, uma cadeia nova recomeça na versão 1. Sem o `queryId`, a
consulta de uma cadeia antiga com a mesma versão concluiria a liderança atual.

Se a condição do `INFLIGHT` falha porque a lease foi transferida (T15), o líder antigo conclui só a
própria consulta. Numa transação sem `REUSE#` nem `INFLIGHT#`, grava a `QUERY` com o estado do
resultado (`COMPLETED` quando o provedor respondeu com `FOUND` ou `NOT_FOUND`, `FAILED` na falha e
`UNKNOWN` sem resposta depois do envio), a `ATTEMPT`, a `EVIDENCE` com a sua `fencingVersion`, o
próprio `EnrichmentResolved` (`MISS`, com a própria evidência) e o `CONSUMED`. Ele não faz fan-out: os
seguidores da cadeia passam a esperar o novo líder.

**Pedido já respondido por outra entrega.** Entre a conferência do `CONSUMED` e a conclusão, outra
entrega do mesmo pedido pode tê-lo respondido por `HIT` ou `JOIN`, e o `CONSUMED` passa a existir. Se a
transação de conclusão falhar só no outbox ou no `CONSUMED` próprios, ela é refeita sem os dois:

- O líder conclui mesmo assim a `QUERY`, o `INFLIGHT#`, o `REUSE#`, a `ATTEMPT` e a `EVIDENCE` e faz o
  fan-out (`SETTLED_ALREADY_ANSWERED`), porque o índice e os seguidores dependem dessa conclusão.
- A conclusão sem liderança conclui a `QUERY` existente do mesmo jeito. É o caso da lease transferida
  e o do líder interrompido cuja liderança já não é a sua (reentrega, abaixo).
- A conclusão sem chamada, que criaria uma consulta nova (§9), não grava nada: o pedido já tem resposta.

O fan-out `JOIN` fica fora da transação de conclusão, porque uma `TransactWriteItems` aceita até 100
ações. Ele lê com `ConsistentRead` só os `FOLLOWER#` da cadeia corrente, da `firstFencingVersion` à
`fencingVersion` do líder, como uma faixa de `sk` (§5). Para cada seguidor, exceto o próprio líder,
uma transação de 2 ações grava o outbox, com `eventId` UUIDv5 de `queryId|workRef` e
`attribute_not_exists`, e o `CONSUMED` do pedido do seguidor. Assim toda reentrega do seguidor é
descartada do mesmo jeito, pelo `CONSUMED`, e nenhum seguidor recebe duas respostas. Sem o limite da
cadeia, uma liderança nova depois de `FAILED` ou de expiração responderia de novo aos seguidores de uma
cadeia concluída, com outro `queryId` e, portanto, outro `eventId`. Quando a reentrega do líder
encontra o `INFLIGHT` `COMPLETED` com o seu `queryId`, refaz o fan-out, que é idempotente.

Se o registro como seguidor for cancelado, o worker relê o `INFLIGHT`. Se a cadeia em que tentou entrar
já concluiu, publica para si o resultado gravado (`JOIN`), inclusive `NOT_FOUND` e `FAILED`, com o
outbox e o `CONSUMED` numa transação; com outra liderança, decide de novo, no mesmo limite de 5
rodadas. Como líder e seguidor escrevem ou conferem o mesmo `INFLIGHT`, todo seguidor de uma cadeia
concluída recebe resposta. O que acontece quando o líder é interrompido está no §9.

**Reentrega do pedido de enriquecimento.** Antes de procurar o `REUSE#`, o worker confere o que o
mesmo `workRef` já gravou, nesta ordem:

1. A `QUERY` do seu `workRef` existe: ele foi líder, ou concluiu sem chamada, e nunca se registra como
   seguidor de si mesmo. Concluída, refaz o fan-out se liderou. Com `IN_FLIGHT`, não repete a chamada.
   Enquanto outra entrega pode estar viva (regra abaixo), adia o pedido e não toca a `QUERY`. Depois
   desse limite, passa a `QUERY` para `UNKNOWN` se houver tentativa aberta (`FAILED` se não houver).
   Conclui a liderança com `FAILED` e faz o fan-out só se o `INFLIGHT` ainda estiver `IN_FLIGHT` com a
   sua `fencingVersion` e o seu `queryId`; senão, conclui só a própria consulta, sem tocar a liderança
   atual. O `queryId` protege contra a versão que recomeça em 1 numa cadeia nova depois do TTL da
   lápide. A mesma regra vale quando o worker encontra no `INFLIGHT` a liderança do próprio `workRef`,
   criada por outra entrega do pedido.
2. O `CONSUMED` do pedido existe: o pedido já foi resolvido, por `HIT`, pelo fan-out ou pelo próprio
   `JOIN`. A chave é o UUIDv5 do `workRef`, não o `eventId` recebido (§3), então a reentrega com outro
   `eventId` também para aqui.
3. Existe `FOLLOWER#<v>#<workRef>` em alguma versão `v` da cadeia corrente, da `firstFencingVersion` à
   `fencingVersion` do `INFLIGHT`: com `COMPLETED`, publica para si o resultado gravado, com o mesmo
   `eventId` do fan-out; com `IN_FLIGHT`, não faz nada, porque o fan-out o alcança. A busca cobre a
   cadeia toda porque um seguidor registrado em `v` continua válido quando outro pedido assume a lease
   em `v+1`.

**Entrega duplicada ainda viva.** A publicação é pelo menos uma vez, então duas entregas do mesmo
pedido podem rodar ao mesmo tempo. Uma `QUERY` `IN_FLIGHT` cuja última `ATTEMPT` (o `startedAt` ou o
`finishedAt` mais recente) é mais nova que `workerTimeoutSeconds` + 5 s pode pertencer a uma entrega
que ainda está no HTTP ou no backoff entre tentativas. Nesse caso, a reentrega não a trata como
interrompida e não abre nova tentativa. Ela reenvia o pedido com `DelaySeconds` até esse limite, pelo
mesmo mecanismo da quota (§9), sem gastar `maxReceiveCount`, e não toca a `QUERY`. Os 5 s cobrem a
diferença de relógio entre instâncias. A regra vale nos dois workers; o limite se apoia no fato de
nenhuma invocação passar do timeout da função (§12).

A conclusão do líder confere a `fencingVersion`, e o fan-out só sai depois de uma conclusão aceita
(T15). Lease expirada não autoriza repetir a chamada sem consultar o estado primeiro (M4).

**Seguradora.** O `insurerWorker` confere o `deadlineAt` (§9), deriva o `queryId` do `workRef` e lê a
`QUERY` antes de qualquer chamada:

| Estado da `QUERY` | Ação |
|---|---|
| Ausente | Segue os três passos abaixo |
| `COMPLETED` ou `FAILED` | Nada: o outbox do resultado já existe |
| `IN_FLIGHT` com `providerRequestId` | Refaz só a metade do worker no `CORR#`, que é idempotente, e nunca chama a seguradora de novo |
| `IN_FLIGHT` com a última `ATTEMPT` (`startedAt` ou `finishedAt`) mais nova que `workerTimeoutSeconds` + 5 s | Pode ser uma entrega duplicada ainda viva, no HTTP ou no backoff: reenvia o pedido com `DelaySeconds` até esse limite, sem gastar `maxReceiveCount`, e não toca a `QUERY` (entrega duplicada ainda viva, acima) |
| `IN_FLIGHT` com tentativa aberta, sem resposta, depois desse limite | Passa para `UNKNOWN` e nunca repete a chamada. A situação não é consultada aqui: quem a consulta (`GET /sim/insurers/beta/quotes/{ref}`) é o `reconciler` ou o `reconcileWorker` (§9); sem API de situação, como na alpha, a consulta abre `UNKNOWN_QUERY` |
| `IN_FLIGHT` sem tentativa aberta (só falhas antes do envio), depois desse limite | Segue com nova `ATTEMPT` se ainda couber em `workerRetries` e no `deadlineAt`; senão, `UNAVAILABLE` (§9) |
| `UNKNOWN` | Nada: a reconciliação cuida (M4) |

1. Antes do HTTP, uma transação grava a `QUERY` (`IN_FLIGHT`, `attribute_not_exists`,
   `clientReference = queryId`), a `ATTEMPT#<nnnn>` com `startedAt` e o débito da quota (`ADD used :1`
   com a condição `attribute_not_exists(used) OR used < :limit`, porque o item da janela pode não
   existir ainda). Sem quota, segue o §9.
2. Chamada HTTP, com `clientReference` quando o protocolo aceita. O callback que devolve o
   `clientReference` encontra a `QUERY#<queryId>` direto.
3. Resposta síncrona: uma transação grava a `EVIDENCE`, a `QUERY` `COMPLETED`, a `ATTEMPT` com
   `finishedAt`, o outbox `InsurerResultRecorded` (`eventId` = UUIDv5 do `queryId`) e o `CONSUMED` do
   pedido. Resposta `202`: uma transação grava o `providerRequestId` na `QUERY` (condição
   `state = IN_FLIGHT`) e, na `ATTEMPT` aberta, o `acceptedAt`, o `httpStatus` e o `providerRequestId`;
   depois o worker faz a sua metade do encontro no `CORR#` (abaixo).

O `CONSUMED` é gravado na mesma transação do efeito, nunca antes. O `202` não é o efeito: ele não
encerra a `ATTEMPT` nem grava o `CONSUMED`. Quem encerra a `ATTEMPT` é o callback, e o `CONSUMED` do
pedido da beta entra só na transação que grava o resultado do callback, pela API ou pelo worker. Se um
callback com `clientReference` concluiu a consulta antes de o `202` ser gravado, a transação do `202` é
cancelada pela condição da `QUERY`, e o worker faz mesmo assim a sua metade do `CORR#`: os callbacks que
chegaram só com o `providerRequestId` passam a `PROCESSED`, sem outro resultado.

**Callbacks.** O contrato do `POST /v1/providers/{provider}/callbacks` está em
`docs/api/provider-solution.openapi.yaml`. A `api` confere a assinatura `X-Signature` (`sha256=` seguido
do hexadecimal minúsculo do HMAC-SHA256 do corpo, com o `callback-secret` como chave) sobre os bytes
recebidos, antes de qualquer leitura ou escrita. A comparação é em tempo constante
(`MessageDigest.isEqual`, em `CallbackSignature`). Assinatura ausente, fora do formato ou que não confere
responde 401, e nada é gravado; o log só leva o provedor do caminho quando ele é conhecido. Só depois a
API confere o provedor, o tamanho e o envelope do corpo. O `result` não é validado na entrada: ele é
classificado no processamento, e um `result` ilegível vira `UNAVAILABLE` com
`INVALID_PROVIDER_RESPONSE`, como na resposta síncrona (§9).

| Situação | Resposta |
|---|---|
| Resultado gravado, inclusive só na inbox, quando a consulta já estava concluída | 200 com `externalEventId`, `processingStatus = PROCESSED` e `duplicate` (repetição reconhecida pela inbox) |
| Callback repetido, já processado | 200 com `processingStatus = PROCESSED` e `duplicate = true`; nada muda |
| Callback retido na inbox, à espera da metade do worker no `CORR#`, inclusive a repetição de um callback `UNMATCHED` ainda sem correlação (M4) | 202 com `processingStatus = RECEIVED` |
| Corpo que não é um objeto JSON, ou envelope fora do contrato (`externalEventId`, `providerRequestId` ou `clientReference`, `result` presente) | 400 `INVALID_CALLBACK` |
| Assinatura ausente ou inválida | 401 `INVALID_SIGNATURE` |
| Provedor sem callbacks (só a `beta` os envia) | 404 `UNKNOWN_PROVIDER` |
| Corpo acima de 256 KiB, que não caberia no item `INBOX#` (o DynamoDB limita o item a 400 KB) | 413 `CALLBACK_TOO_LARGE`, sem passar pela inbox, para o callback não ser reenviado para sempre |
| `Content-Type` que não é JSON | 415 |
| Falha da AWS antes de a inbox ficar consistente | 503 `SERVICE_UNAVAILABLE`; o fornecedor reenvia o mesmo callback |

A API grava a `INBOX` com `attribute_not_exists`, e o callback repetido é reconhecido pela inbox. Já
`PROCESSED`, ele não muda nada. Ainda `RECEIVED`, a associação é refeita, porque a entrega anterior pode
ter caído antes dela. O mesmo vale para o `UNMATCHED` (M4): ele continua processável, e a repetição
refaz a associação. Sem correlação, responde 202 `RECEIVED`, e não 200 como já processada; com ela,
processa o callback e responde 200. A associação tenta primeiro o `clientReference`: a
`QUERY#<clientReference>` precisa existir e ser do mesmo provedor e, se já tem `providerRequestId`, do
mesmo. Um `clientReference` que não bate com nenhuma consulta cai no encontro pelo `providerRequestId`.
Nesse encontro, a API faz a
sua metade no `CORR#<provider>#<providerRequestId>`: `UpdateItem ADD pendingEvents :externalEventId`,
criando o item se faltar, com `ReturnValues = ALL_NEW`. A metade do worker, depois do `202`, é
`UpdateItem SET queryId` no mesmo item, com a condição `attribute_not_exists(queryId) OR
queryId = :queryId`, também com `ALL_NEW`. Quem enxergar as duas metades (`queryId` e `pendingEvents`)
processa as pendências, em ordem de `externalEventId`; quem enxerga só a sua deixa o callback `RECEIVED`.
A reentrega do pedido com `providerRequestId` refaz só a metade do worker e nunca chama de novo (tabela
acima). A reconciliação (M4, §9) também grava a metade do worker: na consulta com `202` vencida e depois
de toda leitura da API de situação. Depois da leitura, ela usa o `providerRequestId` da consulta ou,
quando o `202` se perdeu e a consulta não o tem, o que a resposta da situação traz. Assim o callback que
chegou só com o `providerRequestId` é processado como o que chega depois da conclusão, e não fica retido
até virar `UNMATCHED`.

O processamento é idempotente. Uma transação:

- passa a inbox de `RECEIVED` para `PROCESSED`, com condição, grava `queryId` e `processedAt` e remove o
  `gsi2pk`;
- conclui a `QUERY`, `COMPLETED` (`FAILED` quando o resultado é `UNAVAILABLE`), com a condição
  `state IN (IN_FLIGHT, UNKNOWN)`: o callback também conclui uma consulta `UNKNOWN`, porque é a resposta
  que faltava a uma chamada sem resposta depois do envio;
- grava a `EVIDENCE` (`attribute_not_exists`), com a resposta bruta no bucket (§5);
- encerra a `ATTEMPT` aberta, com `finishedAt`, `outcome` e, no `UNAVAILABLE`, `errorClass`;
- grava o outbox `InsurerResultRecorded`, com `eventId` UUIDv5 do `queryId`, e o `CONSUMED` do pedido
  (§5), os dois com `attribute_not_exists`.

Se falha a condição da inbox, outro processamento já a concluiu. Se falha a da `QUERY` ou a da
`EVIDENCE`, a consulta já estava concluída (por outro callback, pela resposta síncrona ou pela
reconciliação): só a inbox passa a `PROCESSED`, sem novo outbox, para não ficar parada em `RECEIVED`. Se
só o outbox, o `CONSUMED` ou a `ATTEMPT` já existiam, a transação é refeita sem eles, como na conclusão
síncrona. A gravação existe nos dois módulos: na `api` (`CallbackInbox` e `CallbackTable`), para o
callback que encontra a consulta, e no `provider-functions` (`PendingCallbacks` e `CallbackLedger`), para
as pendências que o worker processa no encontro. Os dois caminhos gravam os mesmos itens, com as mesmas
condições e o mesmo `eventId`.

O `reconciler` (M4) refaz, com o código do worker, o encontro do callback `RECEIVED` parado há mais de
`inboxStuckAfterSeconds` (30 s, mais que o timeout da `api`). Sem correlação depois de
`unmatchedCallbackAfterSeconds`, o callback vira `UNMATCHED`, com `unmatchedAt` e
`gsi2pk = INBOX_UNMATCHED`, e abre `UNMATCHED_CALLBACK`, visível ao alarme (§9). O `reconciler` continua
tentando o encontro do `UNMATCHED`: quando a correlação aparece, processa o callback, e a ocorrência se
resolve.

**Reconciliação manual.** O `POST /v1/operations/queries/{id}/reconcile` exige `operations:reconcile`
(403 antes de qualquer leitura, T30) e confere que o `QUERYREF#<queryId>` existe e pertence à organização
do operador; senão, responde 404, sem revelar se a consulta existe. Só depois confere a
`Idempotency-Key` e o body `{"reason": "..."}`: campo desconhecido, `reason` ausente, vazio ou acima de
500 caracteres responde 400 `VALIDATION_ERROR`. O motivo é texto livre do operador, só para auditoria, e
nenhuma resposta o devolve. Uma transação grava, todos com `attribute_not_exists`:

1. `IDEMPOTENCY` (`RECONCILE`, escopo `queryId`), com o `operationId` e o `statusUrl`;
2. `OPERATION#<operationId>` em `QUEUED`, com `reason`, `requestedBy` (o `sub` do operador),
   `requestedAt`, `revision` 0 e um `safeSummary` de espera (§4);
3. o outbox `QueryReconcileRequested`, com `eventId` aleatório: a provider deduplica pelo `operationId`
   (§3).

A resposta é 202 com `operationId`, `status = QUEUED` e `statusUrl` (`/v1/operations/<operationId>`, também
no `Location`). O reenvio com a mesma chave e o mesmo body devolve o mesmo `operationId`, também com
`QUEUED`: a situação real é a do `statusUrl`. A mesma chave com outro motivo responde 409
`IDEMPOTENCY_CONFLICT`; a transação cancelada sem registro de idempotência e a falha da AWS antes da
aceitação durável respondem 503 `SERVICE_UNAVAILABLE`, como nas outras mutações.

O `reconcileWorker` grava o `OPERATION#` `RUNNING` (§5), confere que a `QUERY` é da mesma organização,
reconcilia pelo mesmo caminho do `reconciler` (§9) e grava o resultado com o `QueryReconciled` no outbox,
numa transação. A operação já concluída não muda na reentrega. O resultado é `SUCCEEDED` quando a
consulta já estava concluída ou foi concluída agora, e `FAILED` quando ela continua `UNKNOWN`, ainda está
em voo (no prazo ou com uma entrega que pode estar viva) ou não existe. A consulta de outra organização é
tratada como inexistente e não é tocada, para não revelar que ela existe. O `RUNNING` não é publicado, então
na quote a operação passa de `QUEUED` direto ao resultado, quando o `issue-projector` projeta o
`QueryReconciled` (revisão 2) sobre o `OPERATION#` (§3).

O `GET /v1/operations/{operationId}` exige `operations:read` e responde 404 para operação de outra
organização ou inexistente. Devolve `operationId`, `queryId`, `status`, `safeSummary` e `updatedAt`; o
`operationId` e o `queryId` são uma adição compatível ao contrato compartilhado. A provider entrou no M4;
a API, no M5.

## 9. Falhas, retries e orçamento de tempo

Classificação das respostas por protocolo:

| Fornecedor | Resposta | Classificação |
|---|---|---|
| Enriquecimento | 200 com perfil legível | `FOUND` |
| Enriquecimento | 404 declarado pelo provedor, com `notFoundRefs` | `NOT_FOUND` |
| Enriquecimento | 404 sem essa declaração | `FAILED` com `UNDECLARED_NOT_FOUND`, nunca `NOT_FOUND` (RN07) |
| Enriquecimento | 429, 5xx ou falha antes do envio | Transitório; esgotado o orçamento, `FAILED` |
| Enriquecimento | Outros 4xx (autenticação, payload inválido, recusa) | `FAILED`, sem retry, nunca `NOT_FOUND` (RN07) |
| Enriquecimento | 2xx ilegível, inclusive `marketValue` fora do formato decimal | `FAILED` com `INVALID_PROVIDER_RESPONSE` |
| Enriquecimento | Sem resposta depois do envio | `FAILED` para a quote, sem repetição; a `QUERY` fica `UNKNOWN` |
| Seguradora | Oferta | `QUOTED` |
| Seguradora | Recusa | `DECLINED`, sem retry |
| Seguradora | Pendência de dados | `NEEDS_INFORMATION` |
| Seguradora | 429, 5xx ou falha antes do envio | Transitório; esgotado o orçamento, `UNAVAILABLE` |
| Seguradora | Outros 4xx | `UNAVAILABLE`, sem retry |
| Seguradora | 2xx ilegível, inclusive oferta fora dos formatos do contrato | `UNAVAILABLE` com `INVALID_PROVIDER_RESPONSE` |
| Seguradora | `insurerId` sem rota na provider | `UNAVAILABLE` com `UNSUPPORTED_INSURER`, sem chamada |
| Seguradora | `202` com `providerRequestId` | Aceito: o resultado chega por callback e é classificado como a resposta síncrona (§8) |
| Seguradora | `202` sem `providerRequestId`, ou com um fora do formato que o callback aceita (`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`) | `UNAVAILABLE` com `INVALID_PROVIDER_RESPONSE`, sem retry: nenhum callback dele seria aceito |
| Seguradora | Callback com `result` ilegível | `UNAVAILABLE` com `INVALID_PROVIDER_RESPONSE` |
| Seguradora | Sem resposta depois do envio | `UNKNOWN`, sem repetição |

Só `ConnectException` (conexão recusada) e o timeout de conexão contam como falha antes do envio.
Qualquer outra falha de rede, inclusive o timeout de leitura, pode ter chegado ao fornecedor e conta
como sem resposta depois do envio.

Uma oferta fora dos formatos do `InsurerResultRecorded` no AsyncAPI da provider é resposta ilegível,
não oferta. Isso inclui:

- `validUntil` que não é date-time;
- `currency` fora de `^[A-Z]{3}$`;
- `installments.count` menor que 1;
- valor monetário (`premiumTotal`, `installments.amount`, `coverages.insuredAmount`,
  `deductibles.amount`) com sinal, expoente ou separador, porque o pattern é `^\d+(\.\d+)?$`;
- cobertura sem rótulo: `coverages[].label` é obrigatório.

O `marketValue` do perfil de enriquecimento segue o mesmo pattern. O `validUntil` aceito é normalizado
para UTC com milissegundos.

**Dono do retry.** O worker é o único dono do retry: em processo, até `workerRetries` (2), com backoff
exponencial e jitter. Cada retry grava nova `ATTEMPT` e reserva quota. O cliente HTTP fica sem retry
próprio, e a máquina não repete as esperas; o `Retry` do §6 cobre só falhas de invocação da Lambda.

**Prazo.** O worker confere o `deadlineAt` do pedido antes de cada chamada, de cada retry e no
reprocessamento. Com o prazo vencido, grava resultado terminal sem chamar: `UNAVAILABLE` com
`reasonCode = DEADLINE_EXCEEDED_BEFORE_CALL` na seguradora, `FAILED` no enriquecimento. No
enriquecimento, o prazo é conferido antes do índice de reuso: o pedido vencido conclui com `MISS` e
consulta própria, sem liderança, mesmo com evidência válida no índice (§8).

**Quota.** A reserva condicional vale para a seguradora e para o líder do enriquecimento, só no `MISS`;
`HIT` e `JOIN` não consomem. Sem quota na primeira reserva, o worker reenvia a mensagem com
`DelaySeconds` até a virada da janela, se ainda couber no `deadlineAt`, e apaga a original, para não
gastar `maxReceiveCount`. Se não couber, grava `UNAVAILABLE` com `QUOTA_EXHAUSTED` (`FAILED` no
enriquecimento), sem chamada. Quando a quota acaba no meio dos retries, a seguradora segue a mesma
regra, com reenvio atrasado do pedido; o enriquecimento conclui na hora com `FAILED` e
`QUOTA_EXHAUSTED`, sem reenvio.

O `QUOTA#<provider>#<credentialRef>#<janela>` é global: todos os workflows que chamam o mesmo provedor,
com a mesma credencial e na mesma janela de 1 min, dividem o contador, e não só os itens do `Map` de uma
cotação (T21). O limite é um parâmetro por provedor e por stage (§12): 30 por minuto para cada um, a
beta inclusive. Na integração da provider (`QuotaAcrossWorkflowsIT`), 8 pedidos simultâneos de
workflows diferentes, com limite 3, fazem exatamente 3 chamadas. Na seguradora, os 5 excedentes voltam à
fila com atraso até a janela seguinte. No enriquecimento, com um prazo que não alcança a janela seguinte,
os 5 excedentes concluem com `FAILED` e `QUOTA_EXHAUSTED` (`MISS`), sem chamada.

**Invariantes de tempo.** Os valores estão no §12.

- Timeout da função do worker ≥ `providerHttpTimeoutSeconds` × 3 + `retryBackoffMaxSeconds`.
- `WORKER_TIMEOUT_SECONDS` = timeout da função do `enrichmentWorker` e do `insurerWorker`. A reentrega
  usa esse valor para separar uma entrega duplicada viva de uma interrompida (§8), e o
  `ServerlessConfigurationTest` confere a igualdade.
- `enrichmentLeaseSeconds` > timeout da função do `enrichmentWorker`.
- `inflightTombstoneSeconds` ≥ `VisibilityTimeout` × `maxReceiveCount` da `enrichment-requests` e
  `inflightTombstoneSeconds` > `enrichmentLeaseSeconds`. A reentrega do líder refaz o fan-out pela
  lápide do `INFLIGHT` (§8), então ela precisa durar mais que todas as entregas de um pedido na fila; o
  TTL da tabela a expira depois. O `ServerlessConfigurationTest` confere a relação e o TTL da tabela.
- Toda fila tem `VisibilityTimeout` explícito, maior ou igual ao timeout da função que a consome. A AWS
  valida isso ao criar o ESM e recomenda cerca de 6 vezes. O Floci não valida (§13), então o teste de
  configuração do M1 confere a relação. Cada fila tem `maxReceiveCount` próprio.
- `enrichmentTimeoutSeconds` + ceil(`maxInsurers` / `mapConcurrency`) × `insurerTimeoutSeconds` +
  `stepsMarginSeconds` ≤ `quoteDeadlineSeconds`. Um teste de configuração valida a invariante, e o
  `select-insurers` limita a lista a `maxInsurers`. Ela atende ao "limitado pelo prazo restante" do
  PRD (§9), já que o Floci ignora `TimeoutSecondsPath`.
- Reconciliação (M4), conferida pelos testes de configuração das duas soluções:
  `inboxStuckAfterSeconds` > timeout da `api`, e `unmatchedCallbackAfterSeconds` >
  `inboxStuckAfterSeconds`, para o encontro ser refeito antes do `UNMATCHED`;
  `staleOutboxAfterSeconds` ≥ `outboxRepublishAfterSeconds` + 60 s, para uma republicação saudável
  nunca abrir `STALE_OUTBOX`; `inflightTombstoneSeconds` > timeout do `enrichmentWorker` + 5 s + 60 s,
  para o fan-out pendente ser refeito enquanto a cadeia existe; `statusReadBudgetSeconds` +
  `providerHttpTimeoutSeconds` < timeout do `reconciler` da provider, para a última leitura da API de
  situação terminar a tempo da inbox, das DLQs e das métricas; na quote, `resumingRetryAfterSeconds` >
  timeout do `resume` e timeout do `reconciler` ≤ 60 s. Sem teste: `enrichmentTimeoutSeconds` ≤ `enrichmentLeaseSeconds`,
  da qual depende o tratamento da lease vencida (abaixo).

A espera da seguradora assíncrona cabe nos 20 s de `insurerTimeoutSeconds` do stage `local`: no Floci,
a cadeia fria da beta, da `dispatch` à retomada, levou 13,2 s, com 6,8 s de folga (§12). Por isso os
cenários ponta a ponta não aquecem funções antes de rodar.

Um crash do worker só é reentregue depois do `VisibilityTimeout`, que passa do prazo da etapa. A etapa
termina em `TIMED_OUT`, e o resultado que chegar depois vira evidência tardia. Nessa reentrega, a
última `ATTEMPT` já é mais velha que `workerTimeoutSeconds` + 5 s (150 s de visibilidade contra 30 s no
local), então a tentativa é tratada como interrompida pela tabela do §8. Outra coisa é uma entrega
duplicada do mesmo pedido que chega enquanto a primeira ainda roda, no HTTP ou no backoff entre
tentativas: nos dois workers, ela é adiada com `DelaySeconds` até esse limite, sem tocar a `QUERY` (§8).

**`JOIN` e orçamento.** Um seguidor espera o líder dentro da própria espera, de
`enrichmentTimeoutSeconds`. Se o líder conclui dentro do prazo dele, o seguidor costuma receber o
resultado antes do seu prazo, porque o pedido do seguidor foi despachado depois. A exceção é a entrega
fora de ordem, em que o seguidor entra na liderança de um pedido com prazo posterior ao seu. Se o líder
é interrompido, nem o pedido dele nem os seguidores são respondidos dentro da espera de 20 s: a lease
só vence em 30 s, e a reentrega do líder só vem depois da visibilidade de 150 s. As versões terminam
`TECHNICAL_FAILURE` com `ENRICHMENT_FAILED` pelo timeout da espera, sem dado inventado (RN07), e o
resultado que chegar depois vira evidência tardia. É um limite conhecido, aceito sem mudança de
parâmetro.

**Conflitos de transação.** `TransactionConflict` é repetido no código, com backoff e jitter, em até 4
tentativas, porque o SDK não repete esse caso. O Floci não emula o conflito (§13). Esse caminho tem
testes unitários na API da quote (o 503 e o replay da criação, no `QuoteApiContractTest`) e no `resume`
(o reenvio da mensagem em vez de `LATE` ou `DUPLICATE`, no `ResumeCancellationTest`), e é repetido no
M6.

**DLQs e reprocessamento.** Toda fila de trabalho tem DLQ, inclusive a `sim-callbacks`. A
`stream-failures` é só destino de falha do stream: não tem consumidor nem é reprocessada (§7). O
`reconciler` de cada solução reprocessa as DLQs no padrão do ADR 0007; o reprocessamento fica nele para
não criar outra função. A tentativa vai no atributo de mensagem `dlqAttempt`: a entrega original é a
tentativa 1, e `dlqMaxAttempts` conta essa entrega. Com 3, uma mensagem que sempre falha é reenviada
duas vezes à fila de origem e, na terceira chegada à DLQ, apagada com uma ocorrência `DEAD_LETTER`. O
`ApproximateReceiveCount` não serve de contador, porque continua contando depois do redrive (§13).

O reenvio adiado da provider (`DelaySeconds`, sem quota ou diante de uma entrega duplicada viva, §8)
preserva o `dlqAttempt` da mensagem. Sem ele, a contagem de tentativas recomeçaria a cada reenvio, e a
mensagem poderia circular sem fim entre a fila e a DLQ.

Abaixo do limite, a mensagem volta à fila de origem, e a idempotência por `queryId`, `workRef`,
`operationId` e `CONSUMED` impede chamada ou resultado duplicado (T31). O prazo pesa de modo diferente
para pedidos e para resultados:

- Pedidos com chamada externa (`EnrichmentRequested` e `InsurerQuoteRequested`), na provider, são
  apagados com `DEAD_LETTER` depois do `deadlineAt` do pedido, porque nunca se chama depois do prazo. O
  `QueryReconcileRequested` e os callbacks agendados da `sim-callbacks` não têm prazo e param só no
  limite; a mensagem ilegível é apagada na hora, também com `DEAD_LETTER`. O `QueryReconcileRequested`
  descartado termina em `FAILED`, com o `QueryReconciled` (revisão 2), o `OPERATION#` que ainda está
  `RUNNING`; sem isso, a operação ficaria `RUNNING` para sempre.
- No `DEAD_LETTER` de um pedido descartado da provider, o `organizationId` é sempre o da mensagem, mas o
  `queryId` e o `quoteId` só vão quando a consulta é da organização do pedido (M5). O `reconciler` lê a
  `QUERY` do `queryId`: se ela é de outra organização, a ocorrência sai sem `queryId` e sem `quoteId`; no
  pedido de reconciliação, também quando ela não existe, como o `reconcileWorker` a trataria. Num pedido
  de consulta, a `QUERY` derivada do `workRef` é do próprio pedido, mesmo antes de existir, e o par
  continua. Quando a consulta é da organização e a mensagem não traz `quoteId`, como o pedido de
  reconciliação, a ocorrência recebe o `quoteId` gravado na `QUERY`. Sem essa regra, a quote receberia um
  par para gravar no `QUERYREF#` sem que a organização fosse dona da consulta (§3).
- Resultados da `results-dlq`, na quote: o prazo da versão não conta. A mensagem volta à fila `results`
  enquanto a tentativa for menor que `dlqMaxAttempts`, mesmo depois do prazo, e a retomada a grava como
  resultado tardio (`LATE`) ou a descarta pelo `CONSUMED`, se já foi consumida (§8), sem chamada
  externa. No limite, é apagada com `DEAD_LETTER`. A mensagem sem `quoteId` e `version` no `detail`, que
  não identifica a cotação, é apagada na hora, também com `DEAD_LETTER`, como a ilegível da provider. O
  `safeSummary` da ocorrência diz o motivo: "no limite de N tentativas" ou "sem identificar a cotação".
- Eventos da `issues-dlq`, na quote (M5): o mesmo padrão, com reenvio à fila `issues` abaixo de
  `dlqMaxAttempts`. A mensagem identifica o evento pelo `eventId` e pelo `issueId` ou `operationId` do
  `detail`; sem eles, é apagada na hora. No limite, ou sem identificação, vira `DEAD_LETTER` da quote com a
  organização, o `queryId` e o `quoteId` do próprio evento, sem `version`. Essa ocorrência não grava
  `QUERYREF#` e não é `reconcilable`: o par só a situa na listagem, e sem organização ela fica fora da
  listagem, como o callback sem correlação. O `safeSummary` diz "Evento <tipo> descartado da issues-dlq
  na tentativa N" e o motivo ("no limite de N tentativas" ou "sem identificar a ocorrência ou a
  reconciliação").

**Reconciliação (M4).** O `reconciler` de cada solução roda a cada minuto. Cada dever roda isolado: a
falha de um fica no log, não impede os outros, e o ciclo seguinte tenta de novo.

O `reconciler` da quote examina até `reconcilerBatchSize` (50) itens por dever e por ciclo e registra
`reconciler report: …` em todo ciclo. Nos laços de waiters e de versões, a falha de um item também fica
no log e não impede os seguintes. Ele:

- republica `OUTBOX_PENDING` com `gsi2sk` mais antigo que `outboxRepublishAfterSeconds` (já no M1;
  §7), registrando o `eventId`, o `detailType` e o `createdAt` de cada item;
- retoma o waiter `RESUMING` parado há mais de `resumingRetryAfterSeconds` (30 s, mais que os 15 s de
  timeout do `resume`) com o `resumePayload` já gravado, sem pedido novo à provider (T13). No local,
  essa idade passa das esperas de 20 s, então a retomada pelo `reconciler` termina `EXPIRED`; o
  resultado já está gravado (§8);
- varre as versões `ACTIVE_VERSION`, as de prazo mais antigo primeiro, confirma na tabela que seguem
  ativas e consulta a execução com `DescribeExecution`, pelo `executionArn` gravado ou pelo derivado do
  nome (§7). Execução `FAILED`, `TIMED_OUT` ou `ABORTED` é finalizada mesmo antes do prazo: o
  `reconciler` fecha os waiters que ainda esperam e, numa transação condicionada à `revision`, passa os
  `RESULT` em `PENDING` ou `PROCESSING` para `TIMED_OUT`, com `reasonCode = WORKFLOW_FAILED`,
  `WORKFLOW_TIMED_OUT` ou `WORKFLOW_ABORTED`, consolida pelo mesmo código do passo `consolidate`, sem
  apagar ofertas, e grava `TERMINAL`, o outbox `QuoteCompleted` e a ocorrência `WORKFLOW_FAILED` (T20).
  Os waiters fechados nessa finalização não geram `TASK_EXPIRED`: o `WORKFLOW_FAILED` cobre o caso.
  Execução viva, `SUCCEEDED` ou inexistente só abre `STUCK_VERSION` depois de `deadlineAt` +
  `stuckVersionMarginSeconds`, sem mudar a versão;
- repara o resumo do `META` (§4), inclusive o da versão que já consolidou mas cujo `refreshSummary`
  falhou, que ele encontra no índice `SUMMARY_PENDING`;
- confere os waiters do `WAITER_EXPIRED` e abre `TASK_EXPIRED` só pela regra do §8: waiter que expirou
  com um resultado gravado para ele que o `GetExecutionHistory` da execução encerrada não mostra
  entregue. Com a execução viva, o waiter fica no índice para o ciclo seguinte; sem resultado gravado,
  só sai do índice;
- revisa as ocorrências do `ISSUE_OPEN` (M5; `IssueReviewer`), as de `gsi2sk` mais antigo primeiro, e
  resolve a `STUCK_VERSION` cuja versão saiu do `ACTIVE_VERSION` (regra abaixo);
- reprocessa a `results-dlq` (regra acima) e, a partir do M5, a `issues-dlq`. O relatório do ciclo traz
  os contadores `issues` (`open` e `resolved`) e `issueDeadLetters` (`redriven` e `discarded`);
- publica em EMF `ExpiredTasks` (ocorrências `TASK_EXPIRED` abertas no ciclo) e
  `OutboxPendingAgeSeconds` (idade, pelo `createdAt`, do `OUTBOX_PENDING` mais antigo). A linha EMF vai
  direto para a saída padrão, sem o prefixo do log, porque o CloudWatch só extrai métrica de uma linha
  que é um objeto JSON.

**Ciclo de vida das ocorrências da quote (M5).** Só a `STUCK_VERSION` se resolve sozinha
(`QuoteIssues.Type.resolvesItself`). Enquanto aberta, ela fica no índice `gsi2pk = ISSUE_OPEN` (§4), que
na quote só ela usa; as ocorrências projetadas da provider nunca entram nele. A cada ciclo, o
`reconciler` lê até `reconcilerBatchSize` itens do índice, confirma na tabela que a ocorrência segue
`OPEN` e, quando o `V#n` dela já não está `ACTIVE_VERSION` (ou chegou a `TERMINAL`), a resolve: grava
`RESOLVED`, `resolvedAt`, `lastSeenAt` e o `gsi1sk` com o instante, soma 1 à `revision` e remove o `gsi2`,
com a condição `status = OPEN AND revision = :lida`. Se a ocorrência foi observada de novo durante a
revisão, a condição falha, e ela continua aberta. A que segue aberta fica no índice sem mudar o `gsi2sk`.

`WORKFLOW_FAILED`, `TASK_EXPIRED` e o `DEAD_LETTER` da quote ficam `OPEN`: registram um fato já ocorrido
(execução encerrada sem consolidar, resultado não entregue, mensagem descartada), que nenhuma mudança
posterior desfaz, como o `DEAD_LETTER` da provider, e o alarme de cada uma já as conta. O T20 do
`quote-e2e` espera o `WORKFLOW_FAILED` aberto. Decisão do M5: não há reconhecimento manual dessas
ocorrências no MVP, porque ele exigiria mudar o contrato compartilhado dos dois PRDs; é um limite
conhecido, e a listagem com `status=OPEN` cresce com elas. A descrição de `IssueStatus` no
`contracts/openapi.yaml` registra o mesmo limite.

O `reconciler` da provider examina até `maxItems` (100) itens por dever e por ciclo, entrada do
agendamento. O `ReconcileResult` do ciclo traz em `failedDuties` os deveres que falharam e ficaram para o
ciclo seguinte. Ele:

- republica `OUTBOX_PENDING` (já no M1) e abre `STALE_OUTBOX` só quando o item passa de
  `staleOutboxAfterSeconds` pelo `createdAt`, e não pelo `gsi2sk` (§7), e continua pendente depois da
  tentativa de republicar. O `OperationIssueRaised` e o `OperationIssueUpdated` pendentes não abrem
  `STALE_OUTBOX`: com o barramento fora do ar, cada ocorrência geraria outro evento pendente, em cadeia, e
  a idade deles já está no `OutboxPendingAgeSeconds`;
- refaz o fan-out dos itens `FANOUT_PENDING` (§5) concluídos há mais de `workerTimeoutSeconds` + 5 s,
  pela cadeia gravada no item, sem chamada externa, mesmo que outra cadeia já tenha começado;
- trata as consultas `QUERY_IN_FLIGHT` além do `deadlineAt` e sem entrega viva (§8), sem nunca repetir a
  chamada. No enriquecimento, o líder interrompido conclui a liderança com `FAILED` e faz o fan-out, e a
  consulta fica `UNKNOWN`. Na seguradora com `202` ou com tentativa aberta, a consulta fica `UNKNOWN`;
  com `202`, o `reconciler` refaz também a metade do worker no `CORR#`, para um callback já retido na
  inbox concluí-la antes de qualquer leitura da situação. Na seguradora sem tentativa aberta, ela termina
  `UNAVAILABLE` com `DEADLINE_EXCEEDED_BEFORE_CALL`, numa transação condicionada a não existir
  `ATTEMPT#<n+1>` além das n tentativas lidas: uma reentrega do worker que reservou outra tentativa depois
  da leitura pode estar chamando a seguradora, e a conclusão "antes da chamada" seria falsa;
- consulta a situação das consultas `UNKNOWN` de seguradora com API de situação (a beta, como a
  `vehicle-beta-silent`). `COMPLETED` conclui a consulta como o callback que faltava: evidência com a
  resposta bruta em `raw/<queryId>/status-<nnnn>.json`, `ATTEMPT` encerrada, `InsurerResultRecorded`
  (UUIDv5 do `queryId`) e o `CONSUMED` do pedido. `PROCESSING` ou falha da chamada esperam o próximo
  ciclo; 404 abre `UNKNOWN_QUERY`. Depois de toda leitura, grava a metade do worker no `CORR#` (§8). A
  consulta que continua `UNKNOWN` fica no `QUERY_UNKNOWN`, com o `gsi2sk` regravado com o instante da
  conferência (§5). As leituras são em série, cada uma com o timeout HTTP: num ciclo, param no teto de
  `statusReadBudgetSeconds` (30 s) ou, por provedor, depois da primeira leitura sem resposta, e as
  consultas restantes seguem `UNKNOWN` para o ciclo seguinte. Sem o teto, uma seguradora lenta ou fora do
  ar consumiria o timeout do `reconciler` antes da inbox, das DLQs e das métricas. Sem API de situação,
  como na alpha e no enriquecimento, abre `UNKNOWN_QUERY` e tira a consulta do `QUERY_UNKNOWN`;
- refaz o encontro da inbox `RECEIVED` parada e marca `UNMATCHED` o callback sem correlação, com
  `UNMATCHED_CALLBACK` (§8). O callback do `INBOX_UNMATCHED` que segue sem correlação recebe no `gsi2sk` o
  instante da revisão (§5);
- reprocessa as 4 DLQs (regra acima);
- confere as ocorrências do `ISSUE_OPEN`, onde só entram os tipos que se resolvem sozinhos (`DEAD_LETTER`
  não entra), e resolve as que sumiram: a consulta deixou de ser `UNKNOWN`, o callback foi processado ou o
  evento foi publicado (§5). A que continua aberta recebe no `gsi2sk` o instante da revisão;
- publica em EMF `UnmatchedCallbacks` (callbacks `UNMATCHED` ao fim do ciclo), `UnknownQueries`
  (consultas conferidas no ciclo que continuam `UNKNOWN`; a sem API de situação conta só no ciclo em que
  sai do índice) e `OutboxPendingAgeSeconds`. As duas contagens ficam limitadas a `maxItems` por ciclo.

O `reconcileWorker` usa o mesmo caminho das consultas em voo e `UNKNOWN`, para uma só consulta e sem o
teto de leituras da API de situação (§8). Quem consulta a API de situação é só o `reconciler` ou o
`reconcileWorker`; a reentrega do `insurerWorker` apenas marca `UNKNOWN` (§8).

Não há varredura própria do `INFLIGHT#`. A lease vencida de um líder interrompido é tratada pela
consulta `QUERY_IN_FLIGHT` dele além do `deadlineAt`: o `deadlineAt` do pedido vence antes da lease
enquanto `enrichmentTimeoutSeconds` ≤ `enrichmentLeaseSeconds` (20 ≤ 30 no local), porque o pedido é
despachado antes da aquisição.

O caso de borda do M2, o líder que conclui `FAILED` e cai antes do fan-out, foi resolvido pelo item
`FANOUT`, e não pelo tratamento das leases. Como `FAILED` não entra no índice, o pedido equivalente
seguinte começa outra cadeia, e a reentrega do líder não refaz o fan-out, porque isso exige o
`INFLIGHT#` concluído com a própria consulta (§8). O `FANOUT` gravado na conclusão guarda a cadeia
antiga, e o `reconciler` responde aos seguidores dela.

Os workers publicam em EMF `ProviderCalls` e `ProviderFailures`, com as dimensões `Stage` e `Provider`.
Conta como falha a chamada sem resposta de negócio: falha transitória (429, 5xx ou falha antes do
envio), falta de resposta depois do envio, outro 4xx e resposta ilegível.

**Alarmes (M4).** Declarados como `AWS::CloudWatch::Alarm` nas stacks das soluções, com os limiares
do §12 como params do stage: 16 na provider e 6 na quote no M4. O M5 acrescentou na quote os 2 da
`issues-dlq`, com os mesmos limiares da `results-dlq`, e a quote passou a 8. O nome é
`<solução>-<stage>-` seguido do sufixo da tabela. As métricas próprias saem em EMF, nos namespaces `mmda/provider-solution` e
`mmda/quote-solution`, com a dimensão `Stage`.

| Sufixo do alarme | Solução | Métrica |
|---|---|---|
| `<fila>-dlq-age` e `<fila>-dlq-volume`, para `enrichment-requests`, `insurer-requests`, `reconcile-requests` e `sim-callbacks` | provider | `ApproximateAgeOfOldestMessage` e `ApproximateNumberOfMessagesVisible` |
| `stream-failures-age` e `stream-failures-volume` | provider | Idem |
| `results-dlq-age` e `results-dlq-messages`; `issues-dlq-age` e `issues-dlq-messages` (M5); `stream-failures-age` e `stream-failures-messages` | quote | Idem |
| `outbox-pending-age` | As duas | EMF `OutboxPendingAgeSeconds` |
| `unmatched-callbacks` | provider | EMF `UnmatchedCallbacks` |
| `unknown-queries` | provider | EMF `UnknownQueries` |
| `expired-tasks` | quote | EMF `ExpiredTasks` |
| `enrichment-sim-provider-failures`, `alpha-provider-failures` e `beta-provider-failures` | provider | EMF `ProviderFailures` e `ProviderCalls`, com a dimensão `Provider`: `IF(calls > 0, 100 * failures / calls, 0)` na janela de `providerFailureAlarmPeriodSeconds` |

O `expired-tasks` só vê as ocorrências `TASK_EXPIRED` da regra do §8. Um waiter que expira sem
resultado é o timeout normal da etapa e não conta; com a contagem de todo `EXPIRED`, o alarme, de
limiar 1, dispararia a cada timeout de seguradora (T08, T14, T16).

Nenhum alarme tem `AlarmActions`, inclusive os da `issues-dlq`: o disparo e as ações ficam para o M6. No Floci, os alarmes são
criados, inclusive os de metric math, e aparecem no `DescribeAlarms`, mas não disparam (§13).

## 10. Simuladores e cenários

| Rota | Comportamento |
|---|---|
| `POST /sim/enrichment/profiles` | Perfil do veículo e do condutor, do catálogo quando a referência está nele e sintético fora dele (abaixo); 404 `NOT_FOUND` com `notFoundRefs` (`vehicleRef`, `principalDriverRef` ou os dois) para referência que contém `unknown` ou `not-found`, regra que vem antes do catálogo; 503 nas duas primeiras chamadas de cada referência de pedido para `vehicle-flaky`; 504 sempre para `vehicle-enrichment-down` |
| `POST /sim/insurers/alpha/quotes` | Seguradora síncrona: oferta com as coberturas do perfil pedido (M5); `DECLINED` para `vehicle-alpha-declines`; 503 para `vehicle-alpha-down`; perfil de cobertura fora do catálogo, 400 `INVALID_REQUEST`, antes dos cenários |
| `POST /sim/insurers/beta/quotes` | Seguradora assíncrona: recebe `clientReference`, responde `202` com `providerRequestId` e `status = PROCESSING`, grava a situação e agenda o callback na fila `sim-callbacks`, com o atraso no `DelaySeconds`; sem `clientReference` ou sem veículo, 400; perfil de cobertura fora do catálogo, 400 sem agendar nada (M5) |
| `GET /sim/insurers/beta/quotes/{ref}` | Situação atual por `providerRequestId` ou `clientReference`: `PROCESSING` até o `readyAt` e, depois dele, `COMPLETED` com o resultado; 404 para referência desconhecida. Serve à reconciliação (M4) |

Cenários da beta pelo veículo, sete ao todo, todos com `insurerName` "Beta Seguros":

| Veículo | Resultado | Callbacks |
|---|---|---|
| Qualquer outro | Oferta | Um, com o `clientReference`, depois de `betaCallbackDelaySeconds` |
| `vehicle-beta-declines` | `DECLINED` (`RISK_NOT_ACCEPTED`) | Um, como a oferta |
| `vehicle-beta-needs-info` | Pendência em `risk.principalDriver.licenseDate` (`MISSING_LICENSE_DATE`) | Um, como a oferta |
| `vehicle-beta-duplicate` | Oferta | Dois, com o mesmo `externalEventId`; o segundo sai 2 s depois do primeiro |
| `vehicle-beta-late` | Oferta | Um, depois de `betaLateCallbackDelaySeconds`, que passa da espera da seguradora na quote (§12) |
| `vehicle-beta-early` | Oferta | Um, sem atraso e sem `clientReference`, só com o `providerRequestId`, para o encontro no `CORR#` (T12) |
| `vehicle-beta-silent` | Oferta | Nenhum: a etapa termina `TIMED_OUT`. O GET mostra `COMPLETED` com o resultado depois do `readyAt`, para a reconciliação do M4 |

O `result` do callback tem o formato da resposta síncrona da alpha, então a provider classifica os dois
pelas mesmas regras (`InsurerProtocol.classifyCallback`). A beta decide o resultado e agenda os callbacks
antes de responder o `202`. Ela não é idempotente por `clientReference`: um segundo `POST` com o mesmo
`clientReference` recebe outro `providerRequestId` e agenda outros callbacks, e o `BETAREF#` passa a
apontar a situação nova. O worker nunca repete a chamada (§8).

O `simCallbackSender` lê a mensagem da `sim-callbacks`, monta o corpo do callback (`externalEventId`,
`providerRequestId`, `clientReference` quando há, `sentAt` e `result`), assina com o `callback-secret`
(`X-Signature: sha256=<hex>`) e faz o `POST` na `api` da própria stack. O corpo sai só em ASCII, com o
resto escapado em JSON, para os bytes assinados chegarem iguais à `api` depois do API Gateway, qualquer
que seja o charset que cada salto suponha (§13). Uma resposta fora de 2xx falha só aquela mensagem
(`batchItemFailures`), que volta à fila e, depois de `simCallbacksMaxReceiveCount` recebimentos, vai para
a DLQ.

Desde o M5, os simuladores leem o catálogo do laboratório, o mesmo `contracts/fixtures/catalog.json` da
quote e do frontend (abaixo). O comportamento de cada cenário continua decidido pelo id do veículo; o
catálogo dá os dados:

- Enriquecimento: para veículo do catálogo, marca, modelo e ano vêm dele. Para condutor do catálogo, os
  anos de habilitação são os anos completos desde o `licenseDate` até a data civil de
  `America/Sao_Paulo` (`EnrichmentSimulatorController.CIVIL_ZONE`), e não a de UTC; zero quando o
  `licenseDate` é nulo. Valor de mercado e sinistros continuam sintéticos, porque o catálogo não os tem.
  A regra por id (`unknown` ou `not-found` dá 404) vem antes do catálogo, então o `vehicle-not-found` e o
  `driver-not-found`, que estão no catálogo, continuam 404.
- Seguradoras: as coberturas da oferta são as do perfil pedido (`risk.coverageProfile.code`, ou o próprio
  texto) e, sem perfil no pedido, as do `COMPREHENSIVE`. A franquia é calculada sobre o valor segurado da
  `COLLISION`: 5% na alpha e 4% na beta. Perfil sem `COLLISION` sai sem franquia. Até o M4, a franquia
  saía do valor de mercado.
- Fora do catálogo, a referência continua 100% sintética e determinística, o que preserva os testes
  antigos. O ano do veículo sai de um ano de quatro dígitos na referência (`vehicle-classic-1998` dá 1998)
  ou, sem ele, de um hash da referência entre 2012 e 2023, sempre a partir de 2000. No catálogo, o
  `vehicle-classic-1998` tem o ano 1998. Por isso só um veículo com ano anterior a 2000 exercita o
  `NO_APPLICABLE_INSURER`.

A classe `LabCatalog` lê o arquivo do classpath. Se um id de veículo ou de condutor se repete entre as
organizações, se um perfil de cobertura se repete ou se falta o `COMPREHENSIVE`, a Lambda falha ao subir:
o fornecedor recebe só a referência, então os ids precisam ser únicos.

O estado dos simuladores fica na tabela `provider-solution-<stage>-simulators`, com TTL, que só a
Lambda `simulators` acessa. O contador do `vehicle-flaky` é por referência do pedido (o
`clientReference`, que é o `queryId` enviado pelo worker), então cada execução começa nova. A situação
da beta fica em `BETA#<providerRequestId>`, com o auxiliar `BETAREF#<clientReference>`, os dois com TTL
de 1 dia (§5).

| Cenário | Estado de que depende |
|---|---|
| `vehicle-flaky` | Contador `FLAKY#<clientReference>` na tabela dos simuladores |
| Toda a beta | Situação em `BETA#<providerRequestId>` e `BETAREF#<clientReference>`, para o GET de reconciliação |
| Toda a beta, menos `vehicle-beta-silent` | Callbacks agendados na fila `sim-callbacks` |
| T11 | `callback-secret` da provider, lido pelo harness do e2e para reenviar o callback assinado (abaixo) |
| T21 | `QUOTA#alpha` da janela atual e da seguinte, gravados e apagados pelo harness do e2e (abaixo) |
| T01, T04, T05 e T06 (reuso) | `REUSE#` e `INFLIGHT#` da provider: o harness do e2e apaga os da `queryKey` do par veículo/condutor do caso antes dele, pelo DynamoDB do Floci (abaixo) |
| T25 e T27 | `REUSE#` do par, só lido, sem limpeza: no T25, ele é `NOT_FOUND` e aponta para a evidência usada; no T27, ele não existe, porque `FAILED` nunca entra no índice |

O harness do `quote-e2e` lê o segredo `provider-solution/<stage>/query-key-secret` do Floci e calcula
a `queryKey` como a provider: HMAC-SHA256 de
`organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef` (§5). Antes de T01,
T04, T05 e T06, ele apaga o `REUSE#` e a partição `INFLIGHT#` (`META` e `FOLLOWER#`) do par; as
consultas e evidências anteriores ficam. O T05 vence a validade regravando `REUSE#.reusableUntil` no
passado. Cada caso de reuso tem um par veículo/condutor próprio (§14).

O harness lê também o `provider-solution/<stage>/callback-secret` do Floci local. No T11, depois da
conclusão, ele reenvia o callback guardado na inbox, assinado como o fornecedor, e a `api` responde 200
com `duplicate = true`; depois republica no barramento o `InsurerResultRecorded` do outbox, e a
retomada o descarta pelo `CONSUMED`. Como o descarte não grava nada, o harness espera a linha
`result <eventId> already consumed, discarded` no grupo `/aws/lambda/quote-solution-<stage>-resume`,
pelo `FilterLogEvents` do CloudWatch Logs do Floci; para isso, o `quote-e2e` tem a dependência de teste
`cloudwatchlogs`. Nada muda na cotação nem na consulta. No T21, ele lê o limite da
alpha (`alphaQuotaPerMinute`) do `serverless.yml` da provider, grava o `QUOTA#alpha` da janela atual com
`used` = limite − 2 e o da janela seguinte com `used` = limite, e dispara 5 cotações com o
`vehicle-van-2022` de uso `COMMERCIAL`, que só cota na alpha. Duas terminam `COMPLETED`, três terminam
`TECHNICAL_FAILURE` com a alpha `UNAVAILABLE` e `QUOTA_EXHAUSTED`, e há exatamente 2 `ATTEMPT`. Os dois
itens gravados pelo harness são apagados no `finally`. A quota do enriquecimento entre workflows fica no
`QuotaAcrossWorkflowsIT` (§9).

Regras de laboratório:

- Elegibilidade (`elig-1`, só dados de entrada): veículo com `salvage = true` é `INELIGIBLE`
  (`SALVAGE_VEHICLE`); condutor sem `licenseDate` é `NEEDS_INFORMATION` (`missingFields` com
  `risk.principalDriverRef`, `MISSING_LICENSE_DATE`). Com as duas condições ao mesmo tempo, vale
  `NEEDS_INFORMATION`, na ordem da precedência do PRD (§8): entrada incompleta vem antes da regra que
  impede continuar.
- Enriquecimento obrigatório: `NOT_FOUND` vira `NEEDS_INFORMATION` com `ENRICHMENT_NOT_FOUND`. Cada
  item de `notFoundRefs` vira um `missingFields`: `vehicleRef` → `risk.vehicleRef`,
  `principalDriverRef` → `risk.principalDriverRef`.
- Seleção (`sel-1`, com dados enriquecidos): veículo com ano anterior a 2000 não tem seguradora
  (`NO_APPLICABLE_INSURER`); uso `COMMERCIAL` só cota na alpha; os demais cotam na alpha e na beta. O
  ano vem do perfil enriquecido (`vehicle.year`), com o ano do snapshot como reserva quando o perfil não
  o traz, e é conferido antes do uso. O uso vem do snapshot, porque o perfil do enriquecimento
  (`EnrichmentProfile`, na `provider-domain`) só tem veículo e condutor.
- O `label` de cada `missingFields` vem de uma tabela `path → label` na `quote-domain`.

**Catálogo compartilhado.** Os veículos, condutores, clientes e perfis de cobertura de cada organização
(`org-acme` e `org-globex`) ficam em `contracts/fixtures/catalog.json`. O arquivo é empacotado no
`quote-api` como recurso de classpath (um `<resource>` apontando para `contracts/fixtures`) e a API o lê
do classpath. Desde o M5, o `provider-simulators` empacota o mesmo arquivo do mesmo jeito, em
`fixtures/catalog.json`, sem cópia no módulo. Nos dois módulos, um teste abre o `-lambda.zip` e confere
que a entrada é igual ao arquivo do contrato (`LambdaZipCatalogIT`).

No M5, o catálogo ganhou o `driver-not-found` na `org-acme`, para o laboratório integrado exercitar o 404
com `principalDriverRef` e o caso com as duas referências. A API serve o catálogo em
`/v1/catalog/{customers,vehicles,drivers,coverage-profiles}` (§11), e o `contracts/fixtures/catalog-pages.json`
traz as páginas que ela responde; o T22 do `quote-e2e` confere que o catálogo servido é igual a esse
arquivo, e as coberturas das ofertas do e2e são as do `COMPREHENSIVE` do `catalog.json`.

## 11. Autenticação

A API valida access tokens do user pool Cognito da stack com smallrye-jwt:

```properties
mp.jwt.verify.issuer=${JWT_ISSUER}
mp.jwt.verify.publickey.location=${JWT_ISSUER}/.well-known/jwks.json
smallrye.jwt.path.groups="cognito:groups"
smallrye.jwt.required.claims=token_use,client_id
```

- `JWT_ISSUER` por stage: no local, `http://floci:4566/<poolId>`, igual ao `issuer` do
  `openid-configuration`; na AWS, `https://cognito-idp.<region>.amazonaws.com/<poolId>`. O valor é
  montado com `Fn::Join` do parâmetro `jwtIssuerBase` (§12) e do `Ref` do user pool. `Fn::Sub` não
  serve, porque a sintaxe `${...}` dele conflita com a de variáveis do Serverless.
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
  `InitiateAuth` (`USER_PASSWORD_AUTH`) no host; quem os valida é a Lambda da API. No M5, o `quote-e2e`
  passou a usar quatro usuários, cada um mantido exatamente nos seus grupos, e cria os grupos que faltarem:
  o `e2e-acme-writer` (`quotes:read`, `quotes:write` e `offers:select`), o `e2e-acme-operator` (as cinco
  permissões), o `e2e-acme-viewer` (sem `offers:select` nem `operations:reconcile`) e o
  `e2e-globex-operator` (as cinco, na `org-globex`).

**Contrato e permissões (M5).** O `contracts/openapi.yaml` está na versão 1.0.0 e cobre as 14 operações
da seção "Contrato compartilhado de integração — versão 2.0" dos dois PRDs, com paginação por cursor,
`Idempotency-Key`, o `ConflictError` com os seis códigos de 409 e o erro padrão. Cada operação declara a
permissão na extensão `x-permissions`; sem ela, a resposta é 403 antes de qualquer leitura (T30), e o
`GET /v1/me` só exige o token e a organização:

| Permissão | Operações |
|---|---|
| `quotes:read` | `GET /v1/quotes`, `GET /v1/quotes/{id}/versions/{v}` e `GET .../offers` |
| `quotes:write` | `POST /v1/quotes`, `POST /v1/quotes/{id}/versions` e as quatro rotas `/v1/catalog/*`, porque o catálogo existe para preencher a cotação |
| `offers:select` | `POST /v1/quotes/{id}/versions/{v}/selection` |
| `operations:read` | `GET /v1/operations/issues` e `GET /v1/operations/{operationId}` |
| `operations:reconcile` | `POST /v1/operations/queries/{queryId}/reconcile` |

O contrato acrescenta, de forma compatível, `quoteId` e `version` em `QuoteVersion`, `operationId` e
`queryId` em `Operation`, o parâmetro `limit`, a resposta 415 e a extensão `x-permissions`. O 429, com
`Retry-After`, está declarado em todas as rotas. Decisão do M5: a API não o emite no MVP, porque não há
cota de produto; na AWS, ele pode vir do throttling do API Gateway, conferido no M6. Os 14 arquivos novos
de `contracts/fixtures` (progresso, oferta parcial, recusa, indisponibilidade, pendência, `PARTIAL` com
pendência, expiração, conflitos, erros, seleção, listagem, páginas do catálogo, operações e `/v1/me`)
são trocas conferidas contra o contrato pelo `ContractFixturesTest` ([testes](testing.md)).

**Identificadores de caminho e limites de chave (M5).** Um `quoteId`, `queryId` ou `operationId` de
caminho com mais de 128 caracteres (`Items.fitsKey`, acima de qualquer id da plataforma) responde 404 sem
ler a tabela. Sem isso, o DynamoDB recusaria a chave de partição acima de 2048 bytes com
`ValidationException`, e a API responderia 500. O `maxLength` de `reason` (500), `offerId` (128) e
`search` (100) conta caracteres Unicode (code points), como o JSON Schema, e não unidades UTF-16
(`MutationRequests.characters`).

**Listagens com cursor (M5).** As listagens de cotações e de ocorrências leem o `gsi1` da organização de
quem chama (`IndexPages`), do `gsi1sk` maior para o menor, sem `Scan`. O cursor é o `gsi1sk` do último
item devolvido, em base64url; a chave da tabela sai dele, e o `gsi1pk` vem sempre do token, então um
cursor adulterado não lê outra organização. Uma posição fora do formato `<ordem>#<id>` ou acima de 1024
bytes (`IndexPages.MAX_POSITION_BYTES`, o limite da chave de ordenação) responde 400 `Cursor inválido.`,
em vez de chegar ao DynamoDB. Cada página faz até 10 consultas, de pelo menos 50 itens avaliados cada,
para o filtro que descarta quase tudo não varrer o índice inteiro; por isso uma página pode vir menor que
`limit` e ainda trazer `nextCursor`. Ao mudar os filtros, o cliente recomeça sem cursor.

- `GET /v1/quotes` (`quotes:read`): `gsi1pk = ORG#<organizationId>` e `gsi1sk = <createdAt>#<quoteId>`,
  as mais novas primeiro. Cada item traz `id`, `currentVersion`, `phase`, `outcome`, `createdAt` e
  `offerCount`, do resumo do `META` (§4). `phase` e `outcome` vão em `FilterExpression`. `createdFrom` e
  `createdTo` são inclusivos: a condição da chave limita o período ao segundo, porque o `createdAt` tem
  precisão variável no `gsi1sk`, e o instante exato é conferido no código. Valor fora do enum, instante
  fora do ISO 8601 ou `createdFrom` posterior a `createdTo` respondem 400; o cursor fora do período pedido
  também, porque o DynamoDB recusaria o `ExclusiveStartKey` fora da condição da chave.
- `GET /v1/operations/issues` (`operations:read`): `gsi1pk = ISSUES#<organizationId do operador>` e
  `gsi1sk = <lastSeenAt>#<issueId>`, as vistas por último primeiro, com `issueType` e `status` em
  `FilterExpression` (o `type` do item é `ISSUE`, §4). `type` aceita os sete tipos do contrato, quatro da
  provider e três só da quote. Cada item traz `id`, `queryId`, `quoteId`, `type` (do `issueType`),
  `status`, `firstSeenAt`, `lastSeenAt`, `safeSummary` e `allowedActions` (§8), sem payload, token nem
  motivo informado. Callback sem correlação não tem organização. No MVP, ele não é listado para nenhuma
  organização, porque o contrato não tem papel de operador da plataforma; aparece no alarme de callbacks
  `UNMATCHED` e no log da provider. O mesmo vale para o `DEAD_LETTER` sem organização. `RECONCILE` e
  `GET /v1/operations/{operationId}` conferem a organização do operador (§8).

**Catálogo (M5).** As rotas `/v1/catalog/customers`, `/vehicles` e `/drivers` devolvem só as entradas da
organização do token, com `id` e `label`; `/coverage-profiles` devolve os perfis completos, que valem para
todas. Organização sem fixtures recebe listas vazias de clientes, veículos e condutores. A busca
(`search`, até 100 caracteres) ignora maiúsculas e acentos e procura no rótulo e no id, na ordem do
catálogo. O cursor é o id do último item devolvido, em base64url; um id que não está no resultado da busca
atual responde 400, para o cliente recomeçar.

## 12. Parâmetros por stage

| Parâmetro | local | Uso |
|---|---|---|
| `quoteDeadlineSeconds` | 300 | Prazo total da versão (`deadlineAt`) |
| `workflowMarginSeconds` | 60 | Margem somada ao `TimeoutSeconds` da máquina (rede de segurança) |
| `workflowTimeoutSeconds` | 360 | `TimeoutSeconds` da máquina: `quoteDeadlineSeconds` + `workflowMarginSeconds`, declarado explícito porque o Serverless não soma variáveis; o teste de configuração confere a soma |
| `jwtIssuerBase` | `http://floci:4566` | Base do `JWT_ISSUER`, unida ao id do user pool com `Fn::Join` (§11) |
| `enrichmentTimeoutSeconds` | 20 | Espera do enriquecimento (ver a medição abaixo) |
| `insurerTimeoutSeconds` | 20 | Espera por seguradora (90 na AWS; ver a medição abaixo) |
| `mapConcurrency` | 2 | Itens simultâneos do Map |
| `maxInsurers` | 2 | Seguradoras por versão, no máximo |
| `stepsMarginSeconds` | 30 | Margem dos passos na invariante de orçamento |
| `providerHttpTimeoutSeconds` | 5 | Timeout de conexão e leitura por chamada |
| `workerRetries` | 2 | Retries do worker, além da tentativa inicial |
| `retryBackoffMaxSeconds` | 3 | Soma máxima dos backoffs (exponencial com jitter) |
| `workerTimeoutSeconds` | 25 | Timeout das funções `enrichmentWorker` e `insurerWorker`. Chega aos workers na variável de ambiente `WORKER_TIMEOUT_SECONDS`, que vira `ProviderProperties.workerTimeoutSeconds` (padrão 25), para a reentrega reconhecer uma entrega duplicada viva (§8). O `ServerlessConfigurationTest` confere que a variável e o timeout são iguais |
| `enrichmentLeaseSeconds` | 30 | Lease da liderança; chega às funções da provider em `ENRICHMENT_LEASE_SECONDS` |
| `inflightTombstoneSeconds` | 3600 | TTL da lápide do `INFLIGHT` e dos `FOLLOWER#`; chega às funções da provider em `INFLIGHT_TOMBSTONE_SECONDS` (invariante no §9) |
| `reusePolicyVersion` | `"reuse-v1"` | Versão da política de reuso, gravada na `EVIDENCE`, no `REUSE#` e no `EnrichmentResolved` (§5). É uma string YAML entre aspas, porque sem elas um valor como `2` ou `1.0` viraria número e mudaria o valor publicado; o `ServerlessConfigurationTest` exige `String` no formato. Chega às funções da provider em `REUSE_POLICY_VERSION`; o `ReuseProperties` valida o formato `^[A-Za-z0-9][A-Za-z0-9._-]*$` na subida |
| `enrichmentValiditySeconds` | 900 | Validade do enriquecimento simulado, contada de min(`observedAt`, `receivedAt`) (§5) |
| `offerValiditySeconds` | 1800 | Validade da oferta simulada |
| `enrichmentSimQuotaPerMinute`, `alphaQuotaPerMinute`, `betaQuotaPerMinute` | 30 cada | Quota por provedor e credencial, numa janela de 1 min comum a todos os workflows (§9, T21); chega às funções da provider em `ENRICHMENT_SIM_QUOTA_PER_MINUTE`, `ALPHA_QUOTA_PER_MINUTE` e `BETA_QUOTA_PER_MINUTE` |
| `betaCallbackDelaySeconds` | 3 | Atraso do callback simulado; menor que `insurerTimeoutSeconds` da quote no mesmo stage |
| `betaLateCallbackDelaySeconds` | 30 (120 nos demais stages) | Atraso do callback do `vehicle-beta-late`, maior que `insurerTimeoutSeconds` da quote no mesmo stage (20 no local, 90 na AWS). O `ServerlessConfigurationTest` confere as duas relações; o caso `dev` fica ignorado enquanto a quote não declara a espera desse stage |
| `simCallbackSenderTimeoutSeconds` | 15 | Timeout da função `simCallbackSender` |
| `memorySize` | 1769 | Memória de todas as funções, igual em todos os stages; fica no `provider` de cada `serverless.yml`, fora do bloco `params` (§2) |
| `simCallbacksVisibilityTimeoutSeconds` | 90 | `VisibilityTimeout` da `sim-callbacks`, 6 vezes o timeout do sender |
| `simCallbacksMaxReceiveCount` | 5 | Recebimentos da `sim-callbacks` antes da DLQ |
| `retryAfterSeconds` | 3 | Orientação de polling |
| `outboxRepublishAfterSeconds` | 30 | Idade a partir da qual o `reconciler` republica o outbox |
| `dlqMaxAttempts` | 3 | Tentativas de uma mensagem de DLQ, contando a entrega original: dois reenvios e descarte na terceira chegada à DLQ (ADR 0007, §9). Nas duas soluções; a tentativa vai no atributo `dlqAttempt`. Na `results-dlq` da quote, o prazo da versão não conta: a mensagem é reenviada enquanto a tentativa for menor que o limite, e a que não traz `quoteId` e `version` é descartada na hora |
| `unmatchedCallbackAfterSeconds` | 60 | Prazo, pelo `receivedAt`, para um callback sem correlação virar `UNMATCHED` (M4; provider) |
| `inboxStuckAfterSeconds` | 30 | Idade do callback `RECEIVED` a partir da qual o `reconciler` refaz o encontro; maior que o timeout da `api` (M4; provider) |
| `staleOutboxAfterSeconds` | 120 | Idade do outbox, pelo `createdAt`, que abre `STALE_OUTBOX`; é também o limiar do alarme `outbox-pending-age` da provider (M4) |
| `statusReadBudgetSeconds` | 30 | Teto, por ciclo do `reconciler`, das leituras da API de situação; chega ao `reconciler` em `STATUS_READ_BUDGET_SECONDS`. O `reconcileWorker` não tem teto (M4; provider, §9) |
| `stuckVersionMarginSeconds` | 120 | Margem além do `deadlineAt` para `STUCK_VERSION` (M4; quote) |
| `resumingRetryAfterSeconds` | 30 | Idade do waiter `RESUMING` a partir da qual o `reconciler` o retoma; maior que o timeout do `resume` (M4; quote) |
| `issueProjectorTimeoutSeconds` | 15 | Timeout do `issue-projector`, igual ao do `resume` (M5; quote) |
| `issuesVisibilityTimeoutSeconds` | 90 | `VisibilityTimeout` da fila `issues`, 6 vezes o timeout do `issue-projector` (M5; quote) |
| `issuesMaxReceiveCount` | 5 | Recebimentos da fila `issues` antes da `issues-dlq` (M5; quote) |
| `dlqAlarmVisibleMessages` (provider), `dlqAlarmMessages` (quote) | 1 | Mensagens visíveis que disparam o alarme de volume de cada DLQ e da `stream-failures` (M4; na quote, também a `issues-dlq` desde o M5) |
| `dlqAlarmAgeSeconds` | 300 | Idade da mensagem mais antiga que dispara o alarme de idade de cada DLQ e da `stream-failures` (M4; na quote, também a `issues-dlq` desde o M5) |
| `outboxAlarmAgeSeconds` | 120 | Limiar do alarme `outbox-pending-age` da quote (M4) |
| `unmatchedCallbacksAlarmThreshold`, `unknownQueriesAlarmThreshold` | 1 cada | Limiares dos alarmes `unmatched-callbacks` e `unknown-queries` (M4; provider) |
| `expiredTasksAlarmThreshold` | 1 | Limiar do alarme `expired-tasks` (M4; quote) |
| `providerFailureAlarmPercent`, `providerFailureAlarmPeriodSeconds` | 50 e 300 | Percentual de falhas sobre as chamadas, por provedor, e a janela dele (M4; provider) |
| `alarmPeriodSeconds` | 60 | Período dos alarmes da quote (M4); os da provider usam 60 s fixos, menos os de falhas por provedor |

| Fila | Consumidor | Timeout da função | `VisibilityTimeout` | `maxReceiveCount` |
|---|---|---|---|---|
| quote `results` | `resume` | 15 s | 90 s | 5 |
| quote `issues` | `issue-projector` | 15 s | 90 s | 5 |
| provider `enrichment-requests` | `enrichmentWorker` | 25 s | 150 s | 3 |
| provider `insurer-requests` | `insurerWorker` | 25 s | 150 s | 3 |
| provider `reconcile-requests` | `reconcileWorker` | 25 s | 150 s | 3 |
| provider `sim-callbacks` | `simCallbackSender` | 15 s | 90 s | 5 |

Conferência das invariantes do §9 com esses valores: 5 × 3 + 3 = 18 s ≤ 25 s; `WORKER_TIMEOUT_SECONDS`
= 25 s, o timeout dos workers; lease de 30 s > 25 s; lápide de 3600 s ≥ 150 × 3 = 450 s e > 30 s;
visibilidade de 6 vezes o timeout; 20 + 1 × 20 + 30 = 70 s ≤ 300 s no local e 20 + 1 × 90 + 30 =
140 s ≤ 300 s na AWS. Na reconciliação (M4): inbox parada em 30 s > 29 s da `api`; `UNMATCHED` em
60 s > 30 s; `STALE_OUTBOX` em 120 s ≥ 30 + 60 s; lápide de 3600 s > 25 + 5 + 60 s; teto de leituras
da situação de 30 + 5 s < 60 s do `reconciler` da provider; `RESUMING` retomado em 30 s > 15 s do `resume`; espera do enriquecimento
de 20 s ≤ lease de 30 s.

**Medição no Floci (M1).** A cadeia do T01 com uma seguradora (stream da quote, fila de pedidos, stream
da provider e fila de resultados) foi medida no Floci do `compose.yaml`. A coluna "fria" é a primeira
execução, com os cold starts; a "quente", as seguintes.

| Trecho | Fria | Quente |
|---|---|---|
| Resposta do `POST` | 2,32 s | 0,01 s |
| `POST` até o início da execução | 1,92 s | 0,34 a 0,67 s |
| Enriquecimento | 11,9 s | 3,1 s |
| Seguradora `alpha` | 4,55 s | 2,9 s |
| Execução inteira | 17,96 s | 6,2 s |
| `POST` até `TERMINAL` | 22,25 s | 6,5 a 7,1 s |

Os 20 s de `enrichmentTimeoutSeconds` e `insurerTimeoutSeconds` no stage `local` bastam: o
enriquecimento frio deixa cerca de 8 s de folga. O Floci remove o container de uma Lambda depois de
300 s sem invocação (§13), então os cenários ponta a ponta não podem supor funções aquecidas.

Latência da API aquecida, em 30 requisições sequenciais: `POST` com p50 de 20,3 ms e p95 de 26,7 ms;
`GET` com p50 de 10,3 ms e p95 de 13,3 ms. O teste de carga do PRD (10 criações por segundo durante
5 min) não foi feito.

**Medição no Floci (M2).** Medida no T04 e no T06 do `quote-e2e`, pelos instantes gravados nas tabelas.
Cada célula traz as duas rodadas, separadas por barra.

| Trecho | `MISS` | `HIT` |
|---|---|---|
| `POST` até `TERMINAL` | 6,50 s / 6,73 s | 6,44 s / 6,65 s |
| Espera do enriquecimento, da `dispatch` à retomada | 3,00 s / 2,96 s | 3,01 s / 2,95 s |
| `dispatch` até o worker agir | 1,00 s (liderança) | 0,99 s (`HIT` publicado) |
| Chamada HTTP ao simulador | 34 ms | Nenhuma |
| Conclusão até a retomada | 1,93 s | 1,95 s |

No Floci, o `HIT` só economiza a chamada HTTP e as transações da liderança: a cadeia é dominada pelos
saltos de stream, EventBridge e SQS, com granularidade de cerca de 1 s (§13).

T06 com N = 20, em duas rodadas: os 20 `POST` levaram de 14,7 a 15,6 s, com cold starts de 4
containers da API e 6 da `workflow-steps`. Do `POST` a `TERMINAL`, mínimo de 6,2 s, p50 de 13,5 a
15,5 s e máximo de 21,4 a 21,6 s. A espera do enriquecimento teve mínimo de 4,4 s, p50 de 9,5 a 10,0 s
e máximo de 17,1 a 17,3 s. Houve uma só consulta de enriquecimento, com uma tentativa; os demais
pedidos foram `HIT`, com o mesmo `evidenceId`. Com N = 30, 6 das 30 versões terminaram
`TECHNICAL_FAILURE` com `ENRICHMENT_FAILED`: 3 `HIT` chegaram depois do prazo, e 3 pedidos foram
concluídos pela provider como `FAILED` com `DEADLINE_EXCEEDED_BEFORE_CALL`, sem chamada. Os seis
resultados ficaram guardados como `LATE`. Numa rodada seguinte com N = 20, um `HIT` chegou 0,43 s
depois do prazo, e 1 das 20 versões terminou `TECHNICAL_FAILURE` com `ENRICHMENT_FAILED`.

Com um só container do worker e cerca de 1 mensagem por segundo (§13), o último de N pedidos espera
cerca de N segundos na fila, e essa espera conta dentro dos 20 s de `enrichmentTimeoutSeconds`. Por isso
o T06 do `quote-e2e` usa N = 12 por padrão, ainda configurável por `-De2e.concurrentQuotes`, e espera
50 s + N segundos (§14). A propriedade de 100 pedidos concorrentes fica coberta na integração da
provider (`EnrichmentReuseIT`).

T06 com N = 12, em três rodadas: os 12 `POST` levaram de 9,9 a 11,0 s. A espera do enriquecimento teve
mínimo de 3,2 a 5,8 s, p50 de 5,9 a 8,7 s e máximo de 9,2 a 11,5 s, com pelo menos 8,5 s de folga nos
20 s. Do `POST` a `TERMINAL`, o máximo ficou entre 15,2 e 19,8 s. Nas três, houve um `MISS` e 11 `HIT`,
com uma só consulta e o mesmo `evidenceId`.

T06 com N = 12 na rodada de fechamento do M3, sem build em paralelo: um `MISS` e 11 `HIT`, e os 12
`POST` levaram 10,6 s. Do `POST` a `TERMINAL`, mínimo de 7,4 s, p50 de 15,7 s e máximo de 19,6 s. A
espera do enriquecimento teve mínimo de 3,2 s, p50 de 10,2 s e máximo de 12,4 s, com 7,6 s de folga nos
20 s. Numa rodada anterior, com builds em paralelo na máquina, o máximo chegou a 16,8 s, com 3,2 s de
folga: a folga depende de a máquina estar livre.

**Medição no Floci (M3).** Medida no T01 com duas seguradoras e no T12 do `quote-e2e`, pelos instantes
gravados nas tabelas. A coluna "Frio" é a primeira execução depois do deploy, com as funções frias;
"Sender e API frios", uma execução em que só o `simCallbackSender` e a `api` estavam frios; "Quente", as
seguintes. O atraso do callback da beta é o `betaCallbackDelaySeconds` (3 s).

| Trecho | Frio | Sender e API frios | Quente |
|---|---|---|---|
| T01: `POST` até `TERMINAL` | 29,7 s | 14,8 s | 11,5 s |
| T01: `POST` até a primeira oferta visível (alpha) | 23,7 s | 7,8 s | 6,4 s |
| Alpha: `dispatch` até a retomada | 5,5 s | 3,8 s | 2,8 s |
| Beta: `dispatch` até a retomada | 13,2 s | 10,8 s | 7,8 s |
| Beta: `dispatch` até a chamada | 4,2 s | 0,8 s | 1,8 s |
| Beta: chamada até o `202` gravado | 0,36 s | 0,15 s | 0,18 s |
| Beta: chamada até o callback recebido (atraso de 3 s) | 7,6 s | 7,9 s | 3,9 s |
| Beta: callback até o processamento na `api` | 0,07 s | 0,09 s | 0,06 s |
| Beta: processamento até a retomada | 1,3 s | 2,0 s | 1,9 s |

No T12, com atraso 0, na rodada de fechamento do M3: 8,8 s do `POST` a `TERMINAL`; 3,8 s da `dispatch`
à retomada da beta; 0,94 s da chamada ao callback. A cadeia fria da beta deixa 6,8 s de folga nos 20 s de
`insurerTimeoutSeconds` (§9), então os cenários ponta a ponta não aquecem funções. Com o
`simCallbackSender` ainda em Spring, o primeiro callback depois do deploy levou 28,8 s, com cerca de 7 s
de partida a frio por Lambda, mais que a espera de 20 s: é o motivo de o sender não usar Spring (§1).

**Medição no Floci (M4).** Medida na rodada de integração do M4, com a máquina ociosa e o `quote-e2e`
completo (26 testes em 747,6 s).

| Caso | Tempo |
|---|---|
| T09 | 9,3 s |
| T10 | 9,4 s |
| T13 | 55,0 s |
| T14 | 87,6 s |
| T16 | 42,4 s |
| T17 | 16,6 s |
| T20 | 136,9 s (`dispatch` no ciclo do `reconciler` + 50,4 s, `StopExecution` no ciclo + 54,3 s) |
| T31 | 82,6 s |

Os casos que dependem do `reconciler` duram ciclos dele, e não o tempo da máquina de estado. O
agendamento `rate(1 minute)` do Floci disparou a cada 60,0 ou 70,0 s em 25 intervalos medidos, o que
indica um relógio de 10 s (§13). A reentrega da fila `results` depois da queda do `resume` levou 91 s
nas duas observações, com visibilidade de 90 s (§8 e §13).

Atraso do outbox (publicação menos `createdAt`) na mesma rodada: na quote, p50 de 0,69 s, p95 de
6,2 s e máximo de 8,3 s; na provider, p50 de 0,91 s e máximo de 0,98 s. Os dois ficam abaixo dos 30 s de
`outboxRepublishAfterSeconds`, então o `reconciler` não republica um item que a relay ainda vai
publicar.

T06 com N = 12 na mesma rodada: um `MISS` e 11 `HIT`; os 12 `POST` levaram 9,3 s. Do `POST` a
`TERMINAL`, mínimo de 6,8 s, p50 de 16,9 s e máximo de 21,4 s. A espera do enriquecimento teve mínimo de
2,9 s, p50 de 10,1 s e máximo de 12,3 s, com 7,7 s de folga nos 20 s.

T06 sob carga, numa rodada anterior da integração do M4, com a máquina carregada (load de 7,7 em
15 min): 8 das 12 cotações terminaram `TECHNICAL_FAILURE` com `ENRICHMENT_FAILED`. A primeira leva de
`POST` subiu 11 containers da API ao mesmo tempo, com 18 s de partida a frio. A relay da quote publicou
`QuoteAccepted` e `StartQuoteRequested` até 22,4 s depois do `createdAt`, e a `dispatch` rodou em série,
a cerca de 1,6 s por chamada. Os 11 pedidos `HIT` chegaram depois da espera de 20 s. É o limite que o
próprio T06 descreve (§14), não um defeito do M4: a folga do T06 depende de a máquina estar livre. Mesmo
assim, os 22,4 s ficaram abaixo dos 30 s de `outboxRepublishAfterSeconds`, e nada foi republicado em
dobro.

**Medição no Floci (M5): tempo até a primeira oferta.** É a métrica do PRD (§13, "primeira oferta
persistida menos aceitação durável"), que o plano do PRD (§15) pede no M5. Foi medida no T01 com duas
seguradoras, que lê as ofertas a cada 250 ms. A coluna "No servidor" é o `createdAt` da primeira `OFFER`
menos o `createdAt` do `V#0001`, gravado na transação de aceitação; "Visível no GET" conta do `POST` até a
oferta aparecer na API. "Fria" é a primeira cotação logo depois do deploy, com só os `reconciler` ativos.

| Rodada | Visível no GET | No servidor | `POST` até `TERMINAL` |
|---|---|---|---|
| Fria | 27,51 s | 25,21 s | 32,29 s |
| Quente 1 | 6,29 s | 6,23 s | 11,32 s |
| Quente 2 | 8,35 s | 8,11 s | 11,20 s |
| Quente 3 | 7,03 s | 6,88 s | 11,97 s |
| T01 no `quote-e2e` completo da integração | 7,45 s | 7,40 s | 10,50 s |
| T01 no `quote-e2e` completo da verificação, máquina ociosa | 9,20 s | 8,93 s | 14,08 s |
| T01 no `quote-e2e` completo da verificação, sob carga | 15,74 s | 15,59 s | 21,80 s |

A mediana das três quentes é 7,03 s até a oferta visível. Na fria, quase toda a diferença é partida a
frio: a alpha levou 3,2 s da `dispatch` à chamada e a beta, 8,2 s da chamada ao callback, contra 0,6 s e
3,9 s nas quentes. Na rodada sob carga, a carga da máquina chegou perto de 14. Nas 136 versões com oferta
da tabela implantada, gravadas do M1 ao M5, a mesma métrica do servidor teve mínimo de 6,24 s, p50 de
7,31 s, p95 de 19,63 s, máximo de 23,92 s e média de 8,96 s.

T06 com N = 12 na rodada da verificação com a máquina ociosa: um `MISS` e 11 `HIT`; os 12 `POST` levaram
15,2 s. Do `POST` a `TERMINAL`, mínimo de 14,6 s, p50 de 31,9 s e máximo de 37,3 s. A espera do
enriquecimento teve mínimo de 5,5 s, p50 de 17,4 s e máximo de 18,9 s, com só 1,1 s de folga nos 20 s:
bem menos que os 7,7 s do M4. Na rodada sob carga, o T06 terminou pelo timeout da espera de 62 s
([testes](testing.md)).

## 13. Limites do Floci 2.4.0

Comportamentos verificados nas revisões do desenho, no spike do M0 e na integração do M1 ao M5. "Não
verificado" marca o que nenhum teste cobriu ainda; nessas linhas, a coluna do meio diz a alternativa
caso o Floci não atenda. A coluna M6 marca o que precisa ser repetido na AWS.

| Recurso | Resultado no Floci | M6 |
|---|---|---|
| `TransactWriteItems` com condição | Funciona; cancela tudo e informa o motivo por item | |
| `TransactWriteItems` com 101 ações | `ValidationException` | |
| `TransactionConflict` | Não emulado: 20 transações concorrentes no mesmo item terminam todas com sucesso. Por isso o T06 com 100 pedidos concorrentes no `provider-functions` não exercita o conflito. Com condição, o Floci serializa as transações: numa sonda do M5, com 5 rodadas de 20 transações condicionais concorrentes, cada rodada teve uma vencedora, e nenhuma recebeu `TransactionConflict`. A corrida entre seleção e nova versão (T29; 4 rodadas de 6 pedidos no `QuoteApiFlociIT`) só exercita a condição do `META` | Repetir, com 100 pedidos concorrentes na liderança e com a corrida do T29 |
| Limites de chave e `ExclusiveStartKey` do DynamoDB | Não reproduzidos pelo Floci nem pelo mock dos testes: chave de partição acima de 2048 bytes, de ordenação acima de 1024 e `ExclusiveStartKey` fora da condição da chave não são recusados. Na AWS, as três respondem `ValidationException`. A API não chega a elas (§11), e os testes de contrato da API usam uma tabela simulada que reproduz as recusas (`DynamoDbLimits`, [testes](testing.md)) | |
| `ReturnValuesOnConditionCheckFailure = ALL_OLD` em `TransactWriteItems` | Não verificado. O desenho não depende dele: o conflito de idempotência relê com `GetItem` e `ConsistentRead` (§8) | |
| `StartExecution` repetido com mesmo nome e entrada | Idempotente | |
| `lambda:invoke.waitForTaskToken` e `sqs:sendMessage.waitForTaskToken` | Funcionam | Repetir |
| `SendTaskSuccess` repetido / token inválido | `TaskTimedOut` / `InvalidToken` | |
| Map com `MaxConcurrency` e `ItemSelector` | Funciona | |
| `TimeoutSeconds` e `HeartbeatSeconds` fixos por estado | Funcionam; o timeout do estado é capturável por `Catch` | Repetir |
| `TimeoutSeconds` da máquina | Encerra a execução em `TIMED_OUT` sem passar por `Catch` | Repetir |
| `TimeoutSecondsPath` | Ignorado: a execução fica parada; não usar | |
| SQS e DynamoDB Streams acionando Lambda | Funcionam; cada salto de ESM leva cerca de 1 s | |
| Vazão do ESM de SQS | Com `BatchSize` 1, cerca de 1 mensagem por segundo por função, com um só container do worker (`enrichment-requests` da provider e `results` da quote). API Gateway e invocações síncronas sobem vários containers. Por isso o `JOIN` não aparece no `quote-e2e`, e o T06 usa 12 cotações por padrão: com 20, a maior espera do enriquecimento chegou a 17,1 s, e numa rodada um `HIT` chegou 0,43 s depois dos 20 s de espera (§12) | Repetir, com o `JOIN` ponta a ponta |
| Vazão do ESM de stream | Cerca de 1 lote por segundo: com `BatchSize` 1, 20 registros levaram 20 s; com 100, 1 s | Repetir |
| Retry padrão do ESM de stream | Bloqueia o shard: o registro com erro é reinvocado e os seguintes esperam; com `MaximumRetryAttempts`, ele é descartado e o shard segue (na AWS, o padrão repete por até um dia) | Repetir |
| `FilterCriteria` no ESM de stream | Funciona: com o filtro de `INSERT` e `type = OUTBOX`, só os `INSERT` do outbox chegam; `MODIFY`, `REMOVE` e outros tipos nem invocam a função | |
| `ReportBatchItemFailures` no ESM de stream | Funciona como na AWS: o lote volta a partir do registro que falhou, inclusive os seguintes, e os anteriores não voltam | |
| `MaximumRetryAttempts`, `BisectBatchOnFunctionError` e `onFailure` em SQS no ESM de stream | Funcionam: o lote é dividido até isolar o registro com erro, que é tentado até o limite e vai para a fila de falha; o shard segue. A mensagem de falha só traz shard e faixa de sequência, sem o conteúdo, e os registros vizinhos são reentregues várias vezes | Repetir |
| `ReportBatchItemFailures` no ESM de SQS | Funciona: só a mensagem que falhou é reentregue | |
| `RedrivePolicy` para a DLQ | Funciona, com e sem Lambda: a mensagem vai para a DLQ depois de `maxReceiveCount` recebimentos. O redrive preserva os atributos da mensagem, e o `ApproximateReceiveCount` continua contando na DLQ, sem recomeçar; por isso o reprocessamento conta as tentativas no atributo `dlqAttempt` (§9) | Repetir (tempos reais de DLQ) |
| Reentrega de SQS | Cerca de `VisibilityTimeout` + 5 s (intervalo de polling do ESM). No M4, a reentrega da fila `results` depois da queda do `resume` levou 91 s, com visibilidade de 90 s, nas duas observações | |
| `VisibilityTimeout` × timeout da função no ESM de SQS | Não validado: o Floci aceita visibilidade menor que o timeout, e a AWS rejeita com `InvalidParameterValueException`. O teste de configuração confere a relação (§9) | Repetir |
| `DelaySeconds` por mensagem | Funciona: com 12 s de atraso, a mensagem chegou à função em 13 s, contra 1 s sem atraso. Funciona também na `sim-callbacks`, onde a beta agenda cada callback com o atraso do cenário (§10) | |
| Regra do EventBridge com SQS ou Lambda como alvo | Funciona | |
| EventBridge Scheduler | `rate(5 minutes)` dispara (ADR 0007). `rate(1 minute)` também dispara, nas duas soluções: no T19 do `quote-e2e`, um `OUTBOX_PENDING` que o filtro do stream não entrega foi republicado pelos `reconciler` da quote e da provider, e o teste passou em 45 s. Numa medição anterior do M1, só na quote, a republicação levou de 37 a 66 s, e o T19 terminou com uma única execução. No M4, em 25 intervalos medidos, o `rate(1 minute)` disparou a cada 60,0 ou 70,0 s, o que indica um relógio de 10 s; os casos que esperam o `reconciler` contam com até 70 s por ciclo (§12) | |
| `/v1/{proxy+}` e `/sim/{proxy+}` em `AWS_PROXY` | Funcionam: a API da quote atende `/v1/{proxy+}` e os simuladores da provider, `/sim/{proxy+}`. A rota `POST /v1/providers/{provider}/callbacks` da `api` da provider também | |
| Assinatura do corpo pelo `AWS_PROXY` | Os bytes de um corpo ASCII atravessam o API Gateway intactos, e a assinatura `X-Signature` confere na `api` (`quote-e2e`; o `StreamLambdaHandlerFlociIT` confere o mesmo com o evento de proxy entregue direto ao handler). Corpo fora do ASCII não verificado: o `simCallbackSender` escapa em JSON tudo o que não é ASCII (§10) | Repetir (corpo fora do ASCII) |
| Lambda chamando o API Gateway da própria stack (`PROVIDERS_BASE_URL`) | Funciona: os workers da provider chamam os simuladores por `http://floci:4566/restapis/<id>/local/_user_request_`, e o `simCallbackSender` chama a `api` de callbacks pelo mesmo caminho | |
| Ordem do callback antecipado (`vehicle-beta-early`) | No `quote-e2e`, o callback nunca chega antes do `202`: o `202` é gravado de 0,13 a 0,21 s depois da chamada, e o callback chega de 0,94 a 1,2 s depois, pelo salto do ESM da `sim-callbacks`. A ordem "callback retido na inbox" fica coberta pela integração da provider: no `CallbackApiFlociIT` e no `InsurerCallbackIT`, que passa os callbacks pelo código real do `provider-api` e cobre o encontro no `CORR#` nas duas ordens, inclusive o callback com `clientReference` que conclui a consulta antes de o `202` ser gravado (§14) | |
| CloudWatch Logs das Lambdas | A saída de cada Lambda fica no grupo `/aws/lambda/<função>`, e o `FilterLogEvents` a lê: o T11 do `quote-e2e` confere ali o descarte da retomada (§8) | |
| Partida a frio de Lambda Java com contexto Spring | Cerca de 7 s por Lambda. Com o `simCallbackSender` em Spring, o primeiro callback depois do deploy levou 28,8 s (§12); sem Spring, o sender economiza cerca de 4,6 s (§1) | |
| IAM dos papéis próprios da provider (`SimulatorsRole`, `CallbacksApiRole`, `SimCallbackSenderRole`) | Não aplicado: o Floci não confere IAM (§2) | Repetir |
| Desserialização de `SQSEvent` e `DynamodbEvent` (`aws-lambda-java-events` com `aws-lambda-java-serialization`) | Funciona nas Lambdas da provider (§7) | |
| `serverless-step-functions`: IAM de `waitForTaskToken` e `TimeoutSeconds` padrão em `lambda:invoke` | Não verificado: o Floci não aplica IAM, e as pendências de IAM estão no §2. O desenho declara `TimeoutSeconds` em toda tarefa (§6) e não depende do padrão | Repetir (IAM) |
| `iss` do Cognito | `http://floci:4566/<poolId>`, com o host do `FLOCI_HOSTNAME` mesmo em chamada por `localhost`; igual ao `issuer` do `openid-configuration`. O `jwks_uri` usa o mesmo host. Tokens `RS256` com `kid = <poolId>`; a assinatura confere com o JWKS | Repetir (issuer da AWS) |
| Claims dos tokens do Cognito | Access token: `token_use = access`, `client_id`, `cognito:groups`, sem `aud`. ID token: `token_use = id`, `aud = client_id`, `cognito:groups`. `InitiateAuth` com `USER_PASSWORD_AUTH` devolve tokens sem challenge para usuário `CONFIRMED` | |
| Containers de Lambda ociosos | Removidos depois de 300 s sem invocação (verificação a cada 30 s); a invocação seguinte paga o cold start. Chave `floci.services.lambda.container-idle-timeout-seconds`; a variável de ambiente equivalente não foi testada | |
| CloudFormation de Cognito, KMS, Scheduler, alarmes e EventSourceMapping | Tipos reconhecidos. Os 22 alarmes do M4 e os 2 da `issues-dlq` do M5 são criados, inclusive os de metric math, e o `DescribeAlarms` lista métrica e limiar de cada um; o alarme não dispara | Repetir (disparo e `AlarmActions` dos alarmes) |
| Métricas EMF | A linha EMF dos `reconciler` das duas soluções chega ao CloudWatch Logs como JSON puro. A extração da métrica não foi verificada | Repetir (extração) |
| `DescribeExecution` | Devolve `FAILED`, `TIMED_OUT` e `ABORTED` das execuções encerradas e `ExecutionDoesNotExist` para execução ausente, que o `reconciler` da quote trata como inexistente (§9) | |
| `StopExecution` | Marca a execução `ABORTED`, mas o interpretador do Floci continua executando os estados sem registrá-los no histórico. Na primeira tentativa do T20, isso consolidou a versão com `STEP_TIMEOUT` antes de o `reconciler` rodar; por isso o T20 alinha a parada ao ciclo dele (§14). Sob carga, numa rodada do M5, a continuação gravou `STEP_TIMEOUT` na beta, mas o `reconciler` finalizou a versão antes do `ConsolidateQuote`, o que é legítimo e o T20 ainda não reconhece (§14) | Repetir |
| Vazão da relay e da `dispatch` sob carga | Cai com a máquina carregada: no T06 de uma rodada do M4, com load de 7,7, a relay da quote publicou até 22,4 s depois do `createdAt`, e a `dispatch` rodou em série, a cerca de 1,6 s por chamada (§12) | |

## 14. Testes por marco

| Marco | Casos do PRD | Onde |
|---|---|---|
| M1 | T01 (uma seguradora), T02, T03, T19, T23, T24 | Unitários no `domain`, integração com Floci em `api` e `functions` (o T19 também no `quote-functions`), `quote-e2e` (T01, T02, T03, T19, T23 e T24) |
| M2 | T04, T05, T06, T15, T25, T27 | Unitários em `provider-domain` e `quote-functions`; integração com Floci em `provider-functions` (T04, T05, T06 com 100 pedidos, T15) e `quote-functions` (lado da quote de T04, T25, T27 e `JOIN`); `quote-e2e` (T04, T05, T06, T25 e T27, com 11 testes ao todo) |
| M3 | T01, T07, T08, T11, T12, T21, T26 | Unitários em `provider-domain`, `provider-simulators`, `quote-domain` e `quote-functions`; contrato em `provider-api` e `quote-api`; integração com Floci em `provider-api` (T11, T12), `provider-functions` (T11, T12, T21), `provider-simulators`, `quote-api` (T07, T08) e `quote-functions` (T01, T07, T08, T11, T26); `quote-e2e` (T01 com duas seguradoras, T07, T08, T11, T12, T21 e T26, com 18 testes ao todo) |
| M4 | T09, T10, T13, T14, T16, T17, T20, T31 | Unitários em `provider-domain`, `provider-functions`, `quote-domain` e `quote-functions`; contrato e configuração em `provider-functions`, `quote-api` e `quote-functions`; integração com Floci em `provider-api`, `provider-functions` (T14, T16, T31), `quote-api` (T17) e `quote-functions` (T09, T10, T13, T16, T17, T20, T31); `quote-e2e` (os oito casos, com 26 testes ao todo) |
| M5 | T18, T22, T28, T29, T30 | Unitários em `quote-domain` (seleção e operações) e `provider-simulators` (catálogo); contrato em `quote-api` contra `contracts/openapi.yaml` e as fixtures (T18, T22, T28, T29, T30) e em `quote-functions` (AsyncAPI); integração com Floci em `provider-functions`, `provider-simulators`, `quote-api` (T18, T28, T29, listagem e reconciliação) e `quote-functions` (`issue-projector`, `issues-dlq`, `STUCK_VERSION` e resumo do `META`); `quote-e2e` (os cinco casos, a reconciliação manual e o catálogo com a listagem, com 33 testes ao todo) |
| M6 | Itens marcados no §13 | Stage `dev` na AWS |

Além dos casos do PRD:

- M1: republicador mínimo (`reconciler`) nas duas soluções, com o disparo do agendamento; relay
  registro a registro, com reentrega por `ReportBatchItemFailures`; teste de configuração das
  invariantes de orçamento e de visibilidade × timeout; desserialização de `SQSEvent` e
  `DynamodbEvent` na provider; catálogo dentro do `-lambda.zip`; issuer lido do
  `openid-configuration` nos `@QuarkusTest`; medição da cadeia do enriquecimento (§12); rotas
  `{proxy+}` e chamada ao API Gateway da própria stack, verificadas no Floci (§13).
- M1, variante do T01: o T01 do PRD tem duas seguradoras. No M1, ele roda com uma só, com o
  `vehicle-van-2022` e uso `COMMERCIAL`, que só cota na alpha (§10). O T01 com duas seguradoras entrou
  no M3, com a beta, e a variante do M1 continua no `quote-e2e`.
- M2, testes por módulo: `ReusePlanTest` no `provider-domain` (decisão `HIT`, `JOIN` ou liderança,
  política de reuso, lease e cadeia). `EnrichmentReuseIT` no `provider-functions`, com 17 casos:
  `t04_reuseHitCompletesWithoutExternalCall`, `t05_expiredReuseStartsNewQueryAndKeepsHistory`,
  `t06_hundredEquivalentConcurrentRequestsElectOneLeader` (1 líder, 1 chamada, 99 seguidores e 100
  `EnrichmentResolved` com o mesmo `evidenceId`), `t15_staleLeaderCannotOverwriteAfterLeaseTransfer`,
  `NOT_FOUND` reutilizado, `FAILED` nunca reutilizado, reentregas idempotentes do líder e do seguidor,
  seguidor que encontra a liderança concluída, líder interrompido que conclui `FAILED` e responde aos
  seguidores, e as corridas e reentregas do §8: `observedAt` adiantado que não prolonga a validade,
  pedido deduplicado pelo `workRef` e não pelo `eventId` recebido, reentrega do líder que responde ao
  seguidor que o fan-out não alcançou, líder interrompido de uma cadeia antiga que não toca a liderança
  atual com a mesma versão, líder cujo pedido foi respondido por outra entrega e que conclui mesmo assim
  para o índice e os seguidores, seguidor cujo registro perde para a conclusão, pedido que perde o
  índice para outro líder e usa a evidência nova, e líder que perde o índice na conclusão e relê a
  revisão. A lista com os nomes está em [testes](testing.md). `EnrichmentEvidenceRecordingTest` e casos
  novos no `QuoteFunctionsFlociIT`, no `quote-functions`: o lado da quote de T04 (com `HIT` e `JOIN`),
  T25 e T27, e o contrato do `EnrichmentResolved` conferido também depois do prazo. O T15 fica só na
  integração da provider.
- M2, `quote-e2e`: o harness limpa o reuso do par antes de T01, T04, T05 e T06 (§10). Pares: T01 e T02
  usam `vehicle-van-2022` com `driver-acme-bruno`; T04, `vehicle-sedan-2019` com `driver-acme-ana`;
  T05, `vehicle-classic-1998` com `driver-acme-ana`; T06, `vehicle-classic-1998` com
  `driver-acme-bruno`. O `vehicle-classic-1998` termina `NO_OFFER` sem chamar seguradora. O T04 confere
  que a `queryKey` calculada pelo harness é a mesma gravada pela provider. O T25 aceita `MISS` ou `HIT`,
  porque o `NOT_FOUND` declarado é reutilizável, e confere que o `REUSE#` do par é `NOT_FOUND` e aponta
  para a evidência usada (mesmo `evidenceId`, `queryId` e `policyVersion`). O T27 confere que não há
  `REUSE#` para o par com falha. O T06 dispara N cotações equivalentes juntas (12 por padrão,
  configurável por `-De2e.concurrentQuotes`) e usa uma espera única, que confere todas as cotações a
  cada intervalo de polling. No fechamento do M2, os 11 testes do `quote-e2e` (T01, T02, T03,
  T04, T05, T06, T19, T23, T24, T25 e T27) levaram 191,0 s, e o `mvn verify` dos módulos da provider
  e da quote rodou 341 testes.
- M3, testes por módulo: no `provider-domain`, o `CallbackSignatureTest` e casos novos no
  `InsurerProtocolTest`. O `provider-api`, módulo novo, tem o `CallbackApiContractTest`, o
  `CallbackApiFlociIT` e o `StreamLambdaHandlerFlociIT`. No `provider-functions`, o `InsurerCallbackIT`
  (T11, T12 e pendências) e o `QuotaAcrossWorkflowsIT` (T21). O `provider-functions` tem dependência de
  teste do `provider-api`, só das classes (exclusão `*:*` das transitivas), e a ponte `CallbackApi`, em
  `functions/src/test/java/com/mmda/serverless/provider/api`, usa o `CallbackInbox` real. Assim o
  `InsurerCallbackIT` cobre o encontro no `CORR#` com o código real das duas metades, nas duas ordens,
  inclusive o callback com `clientReference` que conclui a consulta antes de o `202` ser gravado
  (`recordAccepted` devolve `false`), disparado durante a chamada pelo `ProviderStub.beforeAnswering`.
  No `provider-simulators`, o `BetaInsurerSimulatorControllerTest`, o `SimCallbackSenderTest` e casos
  novos no `SimulatorsLambdaFlociIT`. No `quote-domain`, o `RulesTest` e o `ConsolidationTest`
  (precedência com duas seguradoras). No `quote-functions`, o `FunctionTimingConfigurationTest` (Map e
  orçamento), o `ProviderEventsConsumptionTest`, o `QuoteEventsContractTest` e 7 casos novos no
  `QuoteFunctionsFlociIT`. No `quote-api`, o `QuoteApiFlociIT` (T07 e T08) e o `QuoteApiContractTest`
  (versão `PARTIAL`, T08). O que cada classe cobre está em [testes](testing.md).
- M3, `quote-e2e`: 18 testes, os 11 do M1 e do M2 mais o T01 com duas seguradoras, T07, T08, T11, T12,
  T21 e T26. Pares: o T01 com duas seguradoras usa `vehicle-hatch-2021` com `driver-acme-ana`; os casos
  da beta (T07, T08, T11 e T12) usam `driver-acme-bruno`; o T26 usa `vehicle-classic-1998` com
  `driver-acme-ana` e aceita `HIT` ou `MISS`, porque o par é o do T05. O T12 usa o `vehicle-beta-early`,
  mas no Floci o callback não chega antes do `202` (§13); a ordem inversa fica no `CallbackApiFlociIT` e
  no `InsurerCallbackIT`, este com o código real das duas metades. O harness lê o `callback-secret` no
  T11, confere no log da retomada o descarte do evento republicado e grava e apaga o `QUOTA#alpha` no
  T21 (§10). O T21 do enriquecimento fica no `QuotaAcrossWorkflowsIT`. Os números da rodada de
  fechamento do M3 (testes e tempos do `quote-e2e` e do `mvn verify`) ficam só em [testes](testing.md).
- M4: o `POST /v1/quotes/{id}/versions` entra no M4, porque o T17 está no M4; o bloqueio de seleção em
  versão substituída é testado na integração da quote. Também entram o reprocessamento das DLQs, as
  ocorrências das duas soluções, o `reconcileWorker` e os alarmes (no Floci, só a criação, §13). O
  `reconciler` só abre `TASK_EXPIRED` pela regra do §8: waiter que expirou com um resultado gravado para
  ele que não foi entregue à execução, conferido no histórico dela. No M3, `EXPIRED` e `DONE` eram lidos
  do mesmo jeito. Depois de um `quote-e2e` completo com essa regra, a quote tinha uma só ocorrência, o
  `WORKFLOW_FAILED` do T20, e nenhum `TASK_EXPIRED`; antes dela, a mesma rodada abria 16 ocorrências.
- M4, testes por módulo: no `provider-domain`, o `OperationsTest` (ocorrências, `QueryReconciled` e a
  política das DLQs) e o `InsurerStatusTest` (classificação da API de situação). No `provider-api`, um
  caso novo no `CallbackApiFlociIT` (callback `UNMATCHED` repetido). No `provider-functions`, o
  `ReconcilerIT` (cada dever do `reconciler`, o caso de borda do M2, T14 e T16), o `ReconcileWorkerIT`, o
  `DeadLetterReprocessingIT` (T31), o `StatusReadsTest` (teto das leituras da API de situação) e casos
  novos no `ServerlessConfigurationTest` e no `ProducedEventSchemaTest`. No `quote-domain`, o
  `ReconciliationTest`. No `quote-functions`, o `ReconcilerMetricsTest`, o `ExpiredTaskDeliveryTest`
  (entrega do resultado gravado no histórico da execução) e casos novos no
  `FunctionTimingConfigurationTest` e no `QuoteFunctionsFlociIT` (T09, T10, T13, T16, T17, T20, T31,
  `STUCK_VERSION`, `TASK_EXPIRED`, o reparo do `META` e o ciclo completo do `reconciler`). No
  `quote-api`, o T17 no `QuoteApiContractTest` e no `QuoteApiFlociIT`. O que cada classe cobre está em
  [testes](testing.md).
- M4, `quote-e2e`: 26 testes, os 18 do M1 ao M3 mais T09, T10, T13, T14, T16, T17, T20 e T31. Cada caso
  novo usa um par veículo/condutor próprio, apaga o reuso do par no início e no `finally` e espera os
  `reconciler` com prazo, sem pausa fixa. Os números da rodada de fechamento do M4 (testes e tempos do
  `quote-e2e` e do `mvn verify`) ficam só em [testes](testing.md). Adaptações ao laboratório e ao Floci:
  - T09 e T10 rodam só com a alpha. Nenhum veículo faz as duas seguradoras recusarem, e a beta não tem
    cenário de indisponibilidade. Com uso `COMMERCIAL`, que só cota na alpha (§10), o
    `vehicle-alpha-declines` e o `vehicle-alpha-down` fazem recusar ou cair todas as seguradoras
    aplicáveis. O T09 confere `NO_OFFER` com `ALL_INSURERS_DECLINED`, o motivo `RISK_NOT_ACCEPTED` e uma
    só tentativa (sem retry na recusa). O T10 confere `TECHNICAL_FAILURE` com
    `INSURER_TECHNICAL_FAILURE`, nunca `NO_OFFER`, com 1 + `workerRetries` = 3 tentativas, consulta
    `FAILED` e evidência `UNAVAILABLE`.
  - O T13 usa estado fabricado, como o `QUOTA#` do T21: o harness deixa ilegível o token do waiter da
    beta enquanto ele espera, então o `resume` grava `RESUMING`, `resumePayload`, `RESULT`, `OFFER` e
    `CONSUMED` e cai na decifração; depois da queda, o token volta. O caso confere a versão `COMPLETED`
    com as duas ofertas, a retomada pelo `reconciler`, o waiter fora do índice, nenhum `TASK_EXPIRED`,
    nenhum `LATE` e uma só tentativa por seguradora.
  - O T20 se alinha ao ciclo do `reconciler`: como o `StopExecution` não para o interpretador do Floci
    (§13), o caso despacha a cotação cerca de 51 s depois de um ciclo e faz o `StopExecution` com a
    oferta da alpha gravada e a beta em `PROCESSING`. Ele tenta até 3 cotações e registra cada tentativa
    no log; nas rodadas validadas, bastou a primeira. Cada tentativa apaga o reuso do par antes, para o
    enriquecimento ser `MISS` e a latência até a `dispatch`, medida na tentativa anterior e usada no
    alinhamento, continuar valendo. A tentativa descartada, que a
    continuação do Floci consolidou antes do ciclo, confere as invariantes: as ofertas mantidas, nenhum
    `WORKFLOW_FAILED` e o `QuoteCompleted` com o `outcome` da versão. A tentativa que o `reconciler`
    finaliza confere `PARTIAL`, a beta `TIMED_OUT` com `WORKFLOW_ABORTED`, as ofertas mantidas, a versão
    fora do `ACTIVE_VERSION`, o `WORKFLOW_FAILED` aberto e o `QuoteCompleted` no outbox. Limite conhecido,
    visto sob carga numa rodada do M5: o caso classifica a tentativa só pelo `reasonCode` da beta. A
    continuação do Floci gravou `STEP_TIMEOUT` na beta, mas o `reconciler` finalizou a versão antes do
    `ConsolidateQuote` (`TERMINAL` e `WORKFLOW_FAILED` no mesmo instante), o que é legítimo, e o teste
    acusou "consolidated before the reconciler". Decidir pela transição a `TERMINAL` ou pela presença da
    ocorrência evitaria o falso negativo; o teste não mudou.
  - O T31 põe as entregas direto nas DLQs. Primeiro confere a `RedrivePolicy` implantada nas filas
    `results`, `insurer-requests` e `reconcile-requests`. Depois, com uma cotação já concluída, o caso
    põe a versão fora do prazo (`deadlineAt` no passado) antes de pôr o `InsurerResultRecorded` na
    `results-dlq`. Como o prazo da versão não conta (§9), o resultado é reenviado como tentativa 2 e
    descartado pelo `CONSUMED`; o `QueryReconcileRequested` na `reconcile-requests-dlq` é reenviado como tentativa 2, e o
    `QueryReconciled` sai `SUCCEEDED`; o `InsurerQuoteRequested` vencido na `insurer-requests-dlq` é
    descartado com `DEAD_LETTER` e `OperationIssueRaised`. A cotação e a consulta não mudam. A passagem
    real pela `RedrivePolicy`, com as visibilidades do stage, levaria mais de 7 min por mensagem; ela
    fica coberta pelo `QuoteFunctionsFlociIT`
    (`t31_resultThatKeepsFailingReachesTheDlqAndItsReprocessingDoesNotDuplicateIt`) e pelo
    `DeadLetterReprocessingIT`
    (`t31_messageThatAlwaysFailsGoesToTheDeadLetterQueueAndIsDiscardedAfterDlqMaxAttempts`).
  - O T14 e o T16 usam a beta como ela é: o T14 confere, no log do `reconciler` da provider, a consulta
    deixada `UNKNOWN` e concluída pela API de situação sem nova chamada, e o `LATE` na quote sem mudar a
    versão; o T16, o callback tardio guardado como `LATE` sem reabrir a cotação. O T17 cria a v2 com a
    v1 ainda em andamento, e o usuário do e2e ganhou o grupo `perm_offers_select`, para conferir que só
    a versão atual oferece `SELECT_OFFER`.
- M5, o que o M4 deixou e entrou: a publicação do `QueryReconcileRequested` pela API da quote (§8); a
  regra e a fila `issues` da quote, com o `issue-projector`, que grava o `type` do evento no `issueType`
  do item (§3); a `issues-dlq`, com reprocessamento e alarmes (§9); o `GET /v1/operations/issues`, o
  `GET /v1/operations/{operationId}`, o catálogo e a listagem de cotações (§11).
- M5, também do M4: o ciclo de vida das ocorrências da quote. Só a `STUCK_VERSION` se resolve sozinha;
  `WORKFLOW_FAILED`, `TASK_EXPIRED` e `DEAD_LETTER` ficam `OPEN`, sem reconhecimento manual, por decisão
  do M5 (§9). E a corrida herdada do M1 ao M3 (um `refreshSummary` com leitura velha do `V#n` gravando
  no `META` uma fase mais antiga, como `VALIDATING` sobre `QUOTING`) foi fechada: o resumo do `META` só
  avança, em fase e em número de ofertas (§4), e a listagem o lê.
- M5: o endpoint de seleção (§8), a descrição de `QuoteVersion.missingFields` no `contracts/openapi.yaml`
  e o fixture `quote-partial-needs-information.json`: em `PARTIAL`, as pendências ficam em
  `insurerResults[].missingFields`, e o `missingFields` da versão fica vazio, o que o PRD permite (§6). Os
  simuladores passaram a ler o `catalog.json` (§10), e o `DEAD_LETTER` da provider passou a levar o par
  `queryId`/`organizationId` só da consulta da organização do pedido (§9).
- M5, testes por módulo: no `quote-domain`, o `SelectionsTest` e o `OperationsTest`. No `quote-api`, o
  `SelectionContractTest`, o `OperationsContractTest`, o `ContractFixturesTest` e casos novos no
  `QuoteApiContractTest` e no `QuoteApiFlociIT` (T18, T28, T29, listagem e reconciliação). No
  `quote-functions`, casos novos no `QuoteFunctionsFlociIT` (`issue-projector`, `issues-dlq`,
  `STUCK_VERSION`, resumo monotônico e o ciclo do `reconciler`), no `ProviderEventsConsumptionTest`, no
  `QuoteEventsContractTest` e no `FunctionTimingConfigurationTest`. No `provider-simulators`, o
  `LabCatalogTest`, o `LambdaZipCatalogIT` e casos novos nos testes dos três simuladores. No
  `provider-functions`, casos novos no `DeadLetterReprocessingIT` e no `EnrichmentReuseIT`. O que cada
  classe cobre está em [testes](testing.md).
- M5, `quote-e2e`: 33 testes, os 26 do M1 ao M4 mais T18, T22, T28, T29, T30 e dois casos sem número no
  PRD, que usam o prefixo `m5_`: a reconciliação manual (`m5_manualReconciliationOfAConcludedQuery…`) e o
  catálogo com a listagem (`m5_catalogAndQuoteListingPageWithCursorAndFilters`). Toda troca com a API da
  quote, nos 33 testes, passa pelo `ContractClient`, que a confere contra o `contracts/openapi.yaml`. Os
  números da rodada de fechamento ficam só em [testes](testing.md). Adaptações ao laboratório e ao Floci:
  - Decisão do M5: a reconciliação manual de ponta a ponta usa uma `UNKNOWN_QUERY` publicada no barramento
    pelo próprio teste, em nome da provider, apontando a consulta já concluída da alpha de uma cotação do
    `vehicle-flaky`, porque nenhum cenário do laboratório deixa uma consulta `UNKNOWN` de forma
    determinística. O resultado conferido é `SUCCEEDED` ("já concluída"), nas duas tabelas. O teste remove
    no `finally` a ocorrência, o `QUERYREF#` e o `CONSUMED#` que ele criou.
  - O T18 fabrica a oferta expirada: grava no passado o `validUntil` da oferta e o devolve no `finally`.
  - O vencedor do T29 varia entre rodadas: em umas, a seleção vence uma disputa; em outras, a nova versão
    vence as três, e a seleção é feita sem disputa na versão 4. O teste afirma que o `NEEDS_INFORMATION`
    da beta chega depois da seleção, o caso "pendência posterior" do PRD. No Floci, a corrida não gera
    `TransactionConflict` (§13).
- M6, o que o M5 deixou: o IAM efetivo do `IssueProjectorRole` e das permissões novas da API e do
  `reconciler`; o disparo e as `AlarmActions` dos alarmes da `issues-dlq`; os tempos reais de DLQ da fila
  `issues`; o `TransactionConflict` na corrida do T29; o 429 do API Gateway; o T18, o T29 e o T30 com os
  grupos reais do Cognito; os params do stage `dev`; e a conferência de que nenhum `ISSUE#` no formato
  anterior ao ADR 0015 existe nos ambientes da AWS (§4).
- M6: task token, callbacks (inclusive com corpo fora do ASCII), IAM (inclusive os papéis próprios da
  provider), timeouts, `TransactionConflict` (inclusive com 100 pedidos concorrentes na liderança),
  `JOIN` ponta a ponta, retry e divisão de lote do stream, visibilidade × timeout, issuer do Cognito,
  recuperação e disparo dos alarmes. Do M4, ficam o disparo e as `AlarmActions` dos alarmes, a extração
  das métricas EMF, o IAM das permissões novas, os tempos reais de DLQ e o `StopExecution`, repetidos na
  AWS. O caso `dev` do `ServerlessConfigurationTest` que compara os
  atrasos dos callbacks da beta com a espera da seguradora, hoje ignorado, vira asserção quando o M6
  declarar os params da AWS na quote.

Cada teste leva o identificador do caso no nome (por exemplo, `t02_resubmissionReturnsSameQuote`).

**Orçamento de espera do `quote-e2e`.** Os casos do fluxo não têm número próprio de espera: leem o
bloco `params` do `serverless.yml` da quote, no stage do teste. O T01, o T02 e cada cotação do T04
esperam pela invariante do §9 com uma seguradora (20 + 1 × 20 + 30 = 70 s no local). O T05, o T25 e
o T27 terminam sem seguradora e esperam 20 + 0 × 20 + 30 = 50 s. O T06 espera esse prazo mais N
segundos (62 s com o N padrão de 12), numa espera única para todas as cotações: o ESM de SQS do Floci
entrega cerca de 1 mensagem por segundo (§13), então a última cotação começa cerca de N segundos depois
da primeira. O N padrão é 12 porque a espera de cada pedido na fila conta dentro dos 20 s de
`enrichmentTimeoutSeconds`: com 20, a maior espera chegou a 17,1 s, e numa rodada uma versão terminou
`TECHNICAL_FAILURE` (§12). O caso de 100 pedidos concorrentes fica na integração da provider
(`EnrichmentReuseIT`). O T03, o T23 e o T24 terminam nos passos simples e esperam `stepsMarginSeconds`
(30 s). Os casos com duas seguradoras (T01 com duas, T07, T08, T11 e T12) esperam
20 + ceil(2 / 2) × 20 + 30 = 70 s; o T26 termina sem seguradora e espera 50 s; cada cotação do T21
espera a invariante com uma seguradora mais 5 s, uma por cotação disparada (75 s). O polling segue o
`retryAfterSeconds` (3 s). Os casos do M4 que dependem do `reconciler` (T13, T14, T20 e T31) esperam
os ciclos dele com prazo, sem pausa fixa; cada ciclo pode levar até 70 s no Floci (§13).

O T19 é a exceção, porque depende do agendamento e não da máquina de estado. Ele grava, nas tabelas
implantadas da quote e da provider, um `OUTBOX_PENDING` de 10 minutos atrás que a relay nunca vê: o
`INSERT` sai com `type = E2E_PROBE`, e um `UPDATE` posterior troca para `OUTBOX`; o filtro do stream
ignora esse `MODIFY` (§7). O `detail-type` `ReconcilerProbe` não tem regra no barramento. O teste espera
até 3 min, com polling de 5 s, pelo `publishedAt` e pela remoção do `gsi2pk` nas duas tabelas.

Por isso, ao fim de uma rodada do `quote-e2e`, o log dos `reconciler` mostra a republicação de um item
criado 10 minutos antes, como a vista às 23:04 no fechamento do M3. É a sonda do T19, nas duas
tabelas, e não um outbox real atrasado: sem regra para o `ReconcilerProbe`, a republicação não tem
efeito duplicado. Nas duas rodadas completas da integração do M4, cada `reconciler` republicou
exatamente esse item, e nenhum outbox real da provider foi publicado mais de 10 s depois do
`createdAt`. Sob carga, a relay da quote chegou a atrasar 22,4 s, abaixo dos 30 s de
`outboxRepublishAfterSeconds` (§12). Se o atraso passasse do limiar, o `reconciler` publicaria junto com
a relay o mesmo `eventId`, e os consumidores descartariam a repetição (`CONSUMED`, `workRef`,
`ExecutionAlreadyExists`).

**Seleção de módulos no Maven 3.9.** `mvn -pl <solução> -am verify` seleciona só o POM agregador da
solução e não roda teste nenhum. Para rodar os testes de uma solução, liste os módulos, como em
`mvn -pl quote-solution/domain,quote-solution/api,quote-solution/functions -am verify`, ou acrescente
`-amd` (`mvn -pl quote-solution -am -amd verify`).
