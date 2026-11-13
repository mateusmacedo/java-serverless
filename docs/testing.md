# Testes

Os testes seguem a pirâmide: muitos testes rápidos sem infraestrutura na base, alguns contra a AWS
emulada no meio e poucos contra a stack implantada no topo. Cada nível tem seu comando e sua
convenção de nome, para o build do dia a dia não depender de deploy.

| Nível | Convenção | Comando | Precisa de | Tempo aproximado |
|---|---|---|---|---|
| Unitário e contrato | `*Test` | `mvn test` | Nada além do JDK | Segundos |
| Integração com Floci | `*IT` | `mvn verify` | Docker | Cerca de 30 s |
| Ponta a ponta | `*E2E` | `pnpm test:e2e` | Docker, Floci do `compose.yaml` no ar e login no Serverless | Cerca de 5 min, dos quais cerca de 3 min no `QuoteFlowE2E` (191,0 s na última rodada) |

O `mvn verify` roda os dois primeiros níveis em todas as soluções. Os módulos `sample-solution/e2e`,
`receipt-solution/e2e` e `quote-solution/e2e` só entram no build com o perfil `-Pe2e`.

Para verificar uma solução só, liste os módulos dela. No Maven 3.9, `mvn -pl <solução> -am verify`
seleciona só o POM agregador da solução e não roda teste nenhum:

```bash
mvn -B verify -pl quote-solution/domain,quote-solution/api,quote-solution/functions -am
mvn -B verify -pl provider-solution/domain,provider-solution/functions,provider-solution/simulators -am
```

Outra forma é acrescentar `-amd`, que inclui os módulos filhos: `mvn -B verify -pl quote-solution -am -amd`.

## Unitários

Ferramentas: JUnit 6, Mockito e AssertJ. No Spring, nas versões do `spring-boot-starter-test`; no
Quarkus, nas do BOM do Quarkus, com o AssertJ fixado no `quarkus-parent` na mesma versão do Spring.

| Módulo | Classe | O que cobre |
|---|---|---|
| `spring-aws` | `AwsClientsAutoConfigurationTest` | Um client por SDK presente, client omitido quando o SDK falta, client da aplicação preservado, URL do S3 em virtual-host por padrão e em path-style quando configurado |
| `functions` | `ValidateFunctionTest` | Assinatura HMAC conferida com o vetor de teste público do HMAC-SHA256, limite exato, payload acima do limite e payload vazio |
| `functions` | `PersistFunctionTest` | Tabela e atributos gravados no DynamoDB |
| `functions` | `NotifyFunctionTest` | Mensagem no SNS, evento compatível com a regra do EventBridge e erro quando o EventBridge rejeita o evento |
| `functions` | `ReprocessFunctionTest` | Reinício com `requestId-2`, descarte no limite, lote completo, fila e tamanho de lote configurados |
| `functions` | `ReprocessCommandTest` | Ajuste do lote para a faixa de 1 a 10 |
| `api` | `RequestControllerTest` | `202` com o upload e a execução conferidos; `415` e `400` sem chamar a AWS |
| `receipt-functions` | `IssueReceiptHandlerTest` | Gravação condicional por `requestId` e devolução do comprovante já emitido quando o evento se repete |
| `receipt-functions` | `AnnounceReceiptHandlerTest` | `ReceiptIssued` com o comprovante no `detail` e erro quando o EventBridge rejeita o evento |
| `receipt-api` | `ReceiptResourceTest` | `@QuarkusTest` com os clients trocados por `@InjectMock`: consulta `200` e `404`, emissão `202` com `{"detail": ...}` na entrada da execução, `400` e `415` sem iniciar execução |
| `provider-domain` | `ReusePlanTest` | Decisão do pedido de enriquecimento diante do `REUSE#` e do `INFLIGHT#`: `HIT` só com evidência válida, reutilizável e da política de reuso em vigor (`NOT_FOUND` declarado sim, `FAILED` nunca); `JOIN` na lease válida, inclusive no próprio instante `leaseUntil`; liderança na versão seguinte, continuando a cadeia na lease vencida e começando outra depois de `COMPLETED`; nunca seguidor de si mesmo; conclusão que responde só aos seguidores da própria cadeia |
| `quote-functions` | `EnrichmentEvidenceRecordingTest` | Evidência usada gravada com `evidenceId`, `queryId` e `policyVersion` em `HIT`, `JOIN` e `MISS`; `NOT_FOUND` compartilhado com as referências declaradas; evento fora do contrato devolvido à fila sem evidência, antes e depois do prazo do waiter, sem virar evidência tardia; resultado tardio com a referência da evidência. Os 17 eventos fora do contrato cobrem `reuseDecision` ausente ou desconhecido, `evidenceId`, `queryId` ou `policyVersion` ausentes, `policyVersion` vazio, fora do pattern ou que não é texto, `queryId` e `evidenceId` fora do pattern, `HIT` de resultado `FAILED`, `FOUND` sem `result` e `NOT_FOUND` com `notFoundRefs` ausente, vazio, que não é lista, com referência que o pedido não tem ou que não é texto |

Os testes das functions da `sample-solution` simulam os clients do SDK com
`Answers.CALLS_REAL_METHODS`: o código chama a sobrecarga com `Consumer`, que monta o request e chama
a sobrecarga com o request pronto, que é a simulada. Por isso essas classes usam o modo leniente do
Mockito; as verificações de cada teste continuam explícitas. Os handlers da `receipt-solution` chamam
direto a sobrecarga com o request pronto, então os testes deles usam o modo estrito.

## Contrato

`RequestControllerContractTest` confere requisições e respostas do controller contra o
[OpenAPI](api/openapi.yaml), com o `openapi-request-validator-mockmvc`. Nos casos `400` e `415` a
requisição é inválida de propósito, então só a resposta é conferida.

O teste pega divergência de verdade: com a resposta `415` removida do OpenAPI, ele falha com
`validation.response.status.unknown`. Ao mudar a API, atualize o contrato no mesmo commit.

`ReceiptApiContractTest` faz o mesmo para a API da `receipt-solution`, contra o
[OpenAPI da receipt](api/receipt-solution.openapi.yaml), com o `openapi-request-validator-restassured`
como filtro do RestAssured. Ele também pega divergência: com um campo obrigatório a mais no
`Receipt` do contrato, a consulta falha com `validation.response.body.schema.required`.

Entre as soluções, `CompletedRequestContractTest`, na `receipt-solution`, lê o exemplo do
`RequestCompleted` documentado no [AsyncAPI da sample](events/asyncapi.yaml), copiado em
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

| Classe | O que cobre |
|---|---|
| `RequestFlowFunctionsIT` | As quatro funções com S3, SSM, Secrets Manager, DynamoDB, SNS com assinante SQS, EventBridge com regra para a fila de auditoria, SQS e Step Functions reais |
| `RequestControllerFlociIT` | A aplicação HTTP completa gravando no S3 e iniciando uma execução real |

Cada teste cria os recursos de que precisa antes do contexto Spring, com os mesmos papéis do
`serverless.yml`. A imagem do Floci vem da propriedade `floci.image` do POM raiz.

Na `receipt-solution`, o `ReceiptFunctionsIT` é um `@QuarkusTest` com o recurso de teste
`FlociResource`, que sobe o Floci, define as mesmas propriedades de sistema e cria tabela, barramento
e uma fila de captura antes de o Quarkus iniciar. Os clients do Quarkus Amazon Services chegam ao
Floci pela cadeia padrão do SDK, sem `endpoint-override`, como na Lambda. O `receipt-api` tem o
próprio `FlociResource`, que cria a tabela e uma máquina de estado de um passo para o
`ReceiptApiFlociIT`.

| Classe | O que cobre |
|---|---|
| `ReceiptFunctionsIT` | `issue` gravando no DynamoDB e devolvendo o mesmo comprovante na repetição; `announce` publicando `ReceiptIssued`, capturado por regra e fila SQS |
| `ReceiptApiFlociIT` | Consulta de um comprovante gravado na tabela real, `404` para pedido sem comprovante e emissão iniciando uma execução real, com o `detail` conferido no `DescribeExecution` |

Na plataforma de cotação, o M2 acrescenta o `EnrichmentReuseIT`, no `provider-functions`. Cada caso usa
um veículo próprio, então começa sem `REUSE#` nem `INFLIGHT#` da sua `queryKey`, e o `TestClock`, comum
à provider e ao provedor simulado do teste, vence a validade da evidência e a lease sem espera real. No
`quote-functions`, o `QuoteFunctionsFlociIT` ganhou o lado da quote de T04 (com `HIT` e `JOIN`), T25 e
T27.

| Classe | O que cobre |
|---|---|
| `EnrichmentReuseIT` | 17 casos, listados abaixo |
| `QuoteFunctionsFlociIT` (casos do M2) | Lado da quote de T04, com `HIT` e `JOIN` seguindo o mesmo fluxo do `MISS` e guardando a referência da evidência; T25 (`NOT_FOUND` termina `NEEDS_INFORMATION` sem seguradora) e T27 (`FAILED` termina `TECHNICAL_FAILURE` sem seguradora) |

Casos do `EnrichmentReuseIT` (as regras estão no §5 e no §8 do [desenho técnico](quotation-platform.md)):

| Caso | O que cobre |
|---|---|
| `t04_reuseHitCompletesWithoutExternalCall` | `HIT` sem chamada externa |
| `t05_expiredReuseStartsNewQueryAndKeepsHistory` | Evidência vencida abre outra consulta e mantém o histórico |
| `t06_hundredEquivalentConcurrentRequestsElectOneLeader` | 1 líder, 1 chamada, 99 seguidores e 100 `EnrichmentResolved` com o mesmo `evidenceId` |
| `t15_staleLeaderCannotOverwriteAfterLeaseTransfer` | Líder antigo, depois da transferência da lease, conclui só a própria consulta |
| `declaredNotFoundIsReusedWithTheDeclaredReferences` | `NOT_FOUND` declarado é reutilizado com as referências declaradas |
| `failureIsNeverReusedAndTheNextRequestLeadsANewChain` | `FAILED` nunca entra no índice, e o pedido seguinte começa outra cadeia |
| `redeliveriesOfTheLeaderAndOfAFollowerNeverDuplicateTheResolution` | Reentregas do líder e do seguidor não duplicam a resposta |
| `registeredFollowerThatFindsTheLeadershipCompletedPublishesTheRecordedResult` | Seguidor registrado que encontra a liderança concluída publica o resultado gravado |
| `interruptedLeaderConcludesItsLeadershipWithFailureForItsFollowers` | Líder interrompido conclui a liderança com `FAILED` e responde aos seguidores |
| `observedAtAheadOfTheReceptionDoesNotExtendTheValidity` | `observedAt` adiantado fica na evidência como declarado, mas a validade conta do `receivedAt` |
| `requestIsDeduplicatedByItsWorkRefNotByTheEventIdItArrivedWith` | Reentrega com outro `eventId` é descartada pelo `CONSUMED` do `workRef`, sem consulta nova |
| `leaderRedeliveryAnswersAFollowerThatTheFanOutNeverReached` | Reentrega do líder refaz o fan-out e responde ao seguidor que ficou sem resposta |
| `interruptedLeaderOfAnOlderChainLeavesTheCurrentLeadershipWithTheSameVersionAlone` | Líder interrompido de uma cadeia antiga conclui a própria consulta (`UNKNOWN`) sem tocar a liderança atual, que tem a mesma `fencingVersion` e outro `queryId` |
| `leaderWhoseRequestWasAnsweredMeanwhileStillConcludesForTheIndexAndItsFollowers` | Líder cujo pedido foi respondido por outra entrega conclui a consulta, a liderança e o índice, sem o próprio evento, e faz o fan-out |
| `followerWhoseRegistrationLosesToTheConclusionPublishesTheRecordedResult` | Seguidor cujo registro perde para a conclusão publica para si o resultado gravado (`JOIN`) |
| `requestThatLosesTheIndexToAnotherLeaderRereadsItAndUsesTheNewEvidence` | Pedido cuja liderança é cancelada pelo `REUSE#` trocado relê o índice e usa a evidência nova (`HIT`) |
| `leaderThatLosesTheIndexAtTheConclusionRereadsTheRevisionAndStillIndexesItsEvidence` | Líder que encontra o `REUSE#` trocado na conclusão relê a revisão e grava a própria evidência no índice |

Na última rodada, o `mvn verify` dos módulos da provider e da quote, com o `spring-aws` que entra pelo
`-am`, rodou 341 testes em 2 min 10 s, todos aprovados:

| Módulo | Testes |
|---|---|
| `spring-aws` | 5 |
| `provider-domain` | 55 |
| `provider-functions` | 86 (38 unitários e 48 de integração, 17 deles no `EnrichmentReuseIT`) |
| `provider-simulators` | 14 |
| `quote-domain` | 65 |
| `quote-api` | 30 |
| `quote-functions` | 86 (75 unitários, 39 deles no `EnrichmentEvidenceRecordingTest`, e 11 no `QuoteFunctionsFlociIT`) |
| Total | 341 |

```bash
mvn -B verify -pl provider-solution/domain,provider-solution/functions,provider-solution/simulators,quote-solution/domain,quote-solution/api,quote-solution/functions -am
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

`QuoteFlowE2E`, no módulo `quote-solution/e2e`, cobre o M1 e o M2 da plataforma de cotação, com o
barramento da plataforma, a `provider-solution` e a `quote-solution` implantados. São 11 testes:

- **T01, variante com uma seguradora:** `vehicle-van-2022` com uso `COMMERCIAL` cota só na alpha e
  termina `COMPLETED`, com uma oferta selecionável, a evidência do enriquecimento na quote e na provider
  (`MISS`), a evidência da seguradora e uma única execução `SUCCEEDED`. O T01 do PRD, com duas
  seguradoras, entra no M3.
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
  segundo ([desenho técnico](quotation-platform.md), §13).
- **T25:** veículo desconhecido do provedor termina `NEEDS_INFORMATION` (`ENRICHMENT_NOT_FOUND`), com o
  `missingFields` em `risk.vehicleRef` e sem seguradora. A evidência usada pode ser `MISS` ou `HIT`,
  porque o `NOT_FOUND` declarado é reutilizável. O `REUSE#` do par é `NOT_FOUND` e aponta para a
  evidência usada, com o mesmo `evidenceId`, `queryId` e `policyVersion`.
- **T27:** provedor de enriquecimento fora do ar termina `TECHNICAL_FAILURE` (`ENRICHMENT_FAILED`), sem
  perfil inventado e sem seguradora; a evidência usada é `MISS`, e não há `REUSE#` para o par, porque
  `FAILED` nunca entra no índice.

Cada caso de reuso usa um par veículo/condutor próprio: T01 e T02, `vehicle-van-2022` com
`driver-acme-bruno`; T04, `vehicle-sedan-2019` com `driver-acme-ana`; T05, `vehicle-classic-1998` com
`driver-acme-ana`; T06, `vehicle-classic-1998` com `driver-acme-bruno`. O `vehicle-classic-1998`
termina `NO_OFFER` sem chamar seguradora. Antes de T01, T04, T05 e T06, o harness lê o segredo
`provider-solution/<stage>/query-key-secret` do Floci, calcula a `queryKey` do par como a provider
(HMAC-SHA256 de `organizationId|provider|operation|contractVersion|vehicleRef|principalDriverRef`) e
apaga o `REUSE#` e a partição `INFLIGHT#` (`META` e `FOLLOWER#`) dela; as consultas e evidências
anteriores ficam.

O usuário de teste é criado pelas APIs administrativas do Cognito (`AdminCreateUser`,
`AdminSetUserPassword` e `AdminAddUserToGroup`, nos grupos da organização e das permissões), e o
access token vem de `InitiateAuth` com `USER_PASSWORD_AUTH`. Quem valida o token é a Lambda da API. O
orçamento de cada espera é lido do bloco `params` do `serverless.yml` da quote, para o teste esperar o
mesmo prazo que a máquina de estado tem: o T01, o T02 e cada cotação do T04 usam a invariante de
orçamento com uma seguradora (70 s no local); o T05, o T25 e o T27, a mesma invariante sem seguradora
(50 s); o T03, o T23 e o T24, o `stepsMarginSeconds` (30 s). O polling segue o `retryAfterSeconds`
(3 s). O T06 usa uma espera única, que confere todas as cotações a cada intervalo de polling, de 50 s
mais N segundos (62 s com N = 12), porque o worker do Floci recebe cerca de 1 pedido por segundo. O
T19 depende do agendamento, não da máquina: espera até 3 min, com polling de 5 s. Ele também é coberto
pela integração do `quote-functions`.

Na última rodada, os 11 testes do `QuoteFlowE2E` passaram em 191,0 s:

| Teste | Tempo |
|---|---|
| T01 | 13,3 s |
| T02 | 9,2 s |
| T03 | 3,1 s |
| T04 | 18,3 s |
| T05 | 12,3 s |
| T06 (N = 12) | 30,0 s |
| T19 | 70,2 s |
| T23 | 3,1 s |
| T24 | 8,7 s |
| T25 | 15,2 s |
| T27 | 6,1 s |

```bash
docker compose up -d
pnpm test:e2e
```

O `test:e2e` roda o `deploy:local` e depois
`mvn -B verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e,quote-solution/e2e`. O
`deploy:local` empacota os módulos sem rodar testes (`mvn -B -q package -DskipTests`) e faz o deploy
no stage `local`, nesta ordem:

| Script | Stack |
|---|---|
| `deploy:platform` | Barramento da plataforma (`platform/events`) |
| `deploy:sample` | `sample-solution` |
| `deploy:receipt` | `receipt-solution` |
| `deploy:provider` | `provider-solution` |
| `deploy:quote` | `quote-solution` |

Cada script também roda sozinho, como `pnpm deploy:quote`, contra os pacotes já gerados. Para testar
só contra as stacks já implantadas:

```bash
mvn -B verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e,quote-solution/e2e
```

As propriedades `e2e.endpoint` (padrão `http://localhost:4566`) e `e2e.stage` (padrão `local`) dos
`pom.xml` dos módulos `e2e` apontam para outra stack. No `quote-solution/e2e`, a propriedade
`e2e.concurrentQuotes` (padrão 12) define o número de cotações do T06, como em
`-De2e.concurrentQuotes=20`. No Floci, o worker recebe cerca de 1 pedido por segundo, e a espera de
cada pedido na fila conta dentro dos 20 s do enriquecimento. Com 20, a maior espera chegou a 17,1 s, e
numa rodada um `HIT` chegou 0,43 s depois do prazo, com 1 das 20 versões em `TECHNICAL_FAILURE`
([desenho técnico](quotation-platform.md), §12). O caso de 100 pedidos concorrentes fica no
`EnrichmentReuseIT`.

## Limites conhecidos

- A resposta `500` da API não tem teste.
- O suporte do `openapi-request-validator` ao OpenAPI 3.1 é parcial; o contrato atual passou, mas
  recursos específicos do 3.1 podem não ser validados.
- O formato da mensagem do SNS é conferido só no teste de integração, onde há um assinante SQS; a
  stack do exemplo não tem assinante.
- O caminho de falha da `receipt-solution` (execução `FAILED` depois das novas tentativas) não tem
  teste; o exemplo também não tem DLQ para ele.
