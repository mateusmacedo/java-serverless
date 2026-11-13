# Contratos da provider-solution

Contratos escritos à mão da solução de fornecedores da plataforma de cotação, em Spring. O desenho está no
[desenho técnico](../../docs/plataforma-de-cotacao.md) (§3, §5 e §8); a sintaxe é conferida com os comandos
de [Como validar](../../docs/README.md#como-validar).

| Arquivo | Versão | Descreve | Quem produz | Quem consome |
|---|---|---|---|---|
| [`openapi.yaml`](openapi.yaml) | `0.1.0` | `POST /v1/providers/{provider}/callbacks`, com a assinatura `X-Signature` (OpenAPI 3.1) | Seguradoras assíncronas; no laboratório, a beta simulada pelo `simCallbackSender` | Lambda `api` (`provider-api`) |
| [`asyncapi.yaml`](asyncapi.yaml) | `0.5.0` | Eventos publicados no barramento `platform-{stage}-events` com `source` `mmda.provider`: `EnrichmentResolved`, `InsurerResultRecorded`, `QueryReconciled`, `OperationIssueRaised` e `OperationIssueUpdated` (AsyncAPI 3.1) | `outboxRelay`, `reconciler` e, nos callbacks, a `api` | `quote-solution`: filas `results` (os dois primeiros) e `issues` (os três operacionais) |

A provider consome `EnrichmentRequested`, `InsurerQuoteRequested` e `QueryReconcileRequested`, que este
AsyncAPI referencia no [AsyncAPI da quote-solution](../quote-solution/asyncapi.yaml), sem copiar.

Como os testes conferem:

- **OpenAPI:** o `CallbackApiContractTest` (`provider-solution/api`) passa o controller de callbacks pelo
  `openapi-request-validator-mockmvc` num `@WebMvcTest`. O `EventContractTest` (`provider-solution/functions`)
  lê o exemplo de callback da beta e confere que ele vira o `InsurerResultRecorded` documentado.
- **AsyncAPI, lado produtor:** o `EventContractTest` confere que os eventos serializam como os exemplos, e o
  `ProducedEventSchemaTest` valida os eventos de cada desfecho contra os schemas.
- **AsyncAPI, lado consumidor:** na `quote-solution`, o `ProviderEventsConsumptionTest` passa os exemplos
  pelo `resume` e pelo `issue-projector`, o `QuoteEventsContractTest` confere que cada operação `receive`
  aponta uma mensagem que existe aqui, e o `QuoteFunctionsFlociIT` passa os exemplos operacionais, com campos
  trocados, pelo `issue-projector` no Floci.

Os testes leem os arquivos pelo caminho relativo ao módulo (`../../contracts/provider-solution/...`). O detalhe
de cada teste está em [Testes](../../docs/testes.md#contrato).
