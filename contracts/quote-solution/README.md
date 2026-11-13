# Contratos da quote-solution

Contrato de eventos escrito à mão, na versão `0.3.0`, da solução de cotação em Quarkus. O desenho está no
[desenho técnico](../../docs/plataforma-de-cotacao.md) (§3); a sintaxe é conferida com os comandos de
[Como validar](../../docs/README.md#como-validar).

A API HTTP da quote é a API de produto, compartilhada com o frontend: [`contracts/openapi.yaml`](../openapi.yaml)
e as fixtures de [`contracts/fixtures/`](../fixtures), no caminho fixado pelos dois PRDs. Ela não fica nesta
pasta.

| Arquivo                          | Descreve                                                                                                                                                                                                                                                                                                                      | Quem produz                                                                                             | Quem consome                                                                                                                                 |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| [`asyncapi.yaml`](asyncapi.yaml) | Eventos publicados no barramento `platform-{stage}-events` com `source` `mmda.quote` (`QuoteAccepted`, `EnrichmentRequested`, `InsurerQuoteRequested`, `QuoteCompleted`, `OfferSelected` e `QueryReconcileRequested`), o `StartQuoteRequested` interno e as operações `receive` das filas `results` e `issues` (AsyncAPI 3.1) | `outbox-relay` e `reconciler`, a partir do outbox gravado pela API, pela `dispatch` e pela consolidação | `provider-solution`: `EnrichmentRequested`, `InsurerQuoteRequested` e `QueryReconcileRequested`; os outros três não têm regra no laboratório |

Os eventos que a quote consome são os do [AsyncAPI da provider-solution](../provider-solution/asyncapi.yaml),
referenciados nas operações `receive` com `$ref` para `../provider-solution/asyncapi.yaml`.

Como os testes conferem:

- **Lado produtor:** o `QuoteEventsContractTest` (`quote-solution/functions`) valida os eventos que a quote
  grava e os exemplos contra os schemas e confere que o consumo declarado é exatamente o das filas `results` e
  `issues`.
- **Lado consumidor:** na `provider-solution`, o `EventContractTest` lê os exemplos de `EnrichmentRequested`,
  `InsurerQuoteRequested` e `QueryReconcileRequested` pelo consumidor da provider.
- **API de produto:** o `QuoteApiContractTest`, o `SelectionContractTest`, o `OperationsContractTest` e o
  `ContractFixturesTest` (`quote-solution/api`) e o `ContractClient` do `quote-e2e` conferem cada troca contra o
  `contracts/openapi.yaml`.

Os testes leem os arquivos pelo caminho relativo ao módulo (`../../contracts/...`). O detalhe de cada teste está
em [Testes](../../docs/testes.md#contrato).
