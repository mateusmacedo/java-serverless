# Contratos da sample-solution

Contratos escritos à mão, na versão `0.1.0`, da solução de referência em Spring. O desenho está em
[Arquitetura](../../docs/arquitetura.md) e em [Fluxos](../../docs/fluxos.md); a sintaxe é conferida com os
comandos de [Como validar](../../docs/README.md#como-validar).

| Arquivo                          | Descreve                                                                                                                                                                           | Quem produz                                                                                              | Quem consome                                                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| [`openapi.yaml`](openapi.yaml)   | `POST /requests` (OpenAPI 3.1)                                                                                                                                                     | Lambda `api` (Spring MVC)                                                                                | Clientes HTTP e o `RequestFlowE2E`                                                 |
| [`asyncapi.yaml`](asyncapi.yaml) | Estado que atravessa a `request-flow`, notificação no SNS, `RequestCompleted` no EventBridge, mensagens das filas `audit` e `dlq` e comando agendado do `reprocess` (AsyncAPI 3.1) | Lambda `notify` (SNS e `RequestCompleted`), Step Functions (`dlq`) e EventBridge Scheduler (`reprocess`) | `receipt-solution` (`RequestCompleted`), fila `audit` e Lambda `reprocess` (`dlq`) |

Como os testes conferem:

- **OpenAPI:** o `RequestControllerContractTest` (`sample-solution/api`) passa cada requisição e resposta do
  controller pelo `openapi-request-validator-mockmvc`, lendo este arquivo pelo caminho
  `../../contracts/sample-solution/openapi.yaml`.
- **AsyncAPI:** nenhum teste lê o arquivo. O exemplo do `RequestCompleted` foi capturado no Floci e copiado
  no fixture `request-completed-event.json` da `receipt-solution`, que o `CompletedRequestContractTest` lê;
  ao mudar o evento, atualize o exemplo e o fixture juntos.

O detalhe de cada teste está em [Testes](../../docs/testes.md#contrato).
