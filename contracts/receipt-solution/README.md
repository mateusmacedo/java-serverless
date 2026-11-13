# Contratos da receipt-solution

Contratos escritos à mão, na versão `0.1.0`, da solução de comprovantes em Quarkus. O desenho está em
[Arquitetura](../../docs/arquitetura.md) e em [Fluxos](../../docs/fluxos.md#integração-com-a-receipt-solution);
a sintaxe é conferida com os comandos de [Como validar](../../docs/README.md#como-validar).

| Arquivo                          | Descreve                                                                                                                         | Quem produz                                                                                                                                                                      | Quem consome                                                                              |
| -------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| [`openapi.yaml`](openapi.yaml)   | `GET /receipts/{requestId}` e `POST /receipts` (OpenAPI 3.1)                                                                     | Lambda `api` (Quarkus REST)                                                                                                                                                      | Clientes HTTP e o `ReceiptFlowE2E`                                                        |
| [`asyncapi.yaml`](asyncapi.yaml) | `RequestCompleted` consumido do barramento da `sample-solution` e `ReceiptIssued` publicado no barramento próprio (AsyncAPI 3.1) | `RequestCompleted`: `sample-solution`, descrito no [AsyncAPI dela](../sample-solution/asyncapi.yaml), que este arquivo referencia sem copiar; `ReceiptIssued`: Lambda `announce` | `RequestCompleted`: máquina `receipt-flow`; `ReceiptIssued`: nenhuma regra no laboratório |

Como os testes conferem:

- **OpenAPI:** o `ReceiptApiContractTest` (`receipt-solution/api`) usa o `openapi-request-validator-restassured`
  como filtro do RestAssured, lendo este arquivo pelo caminho `../../contracts/receipt-solution/openapi.yaml`.
- **AsyncAPI:** nenhum teste lê o arquivo. O consumo do `RequestCompleted` é conferido pelo
  `CompletedRequestContractTest` com o fixture copiado do exemplo da `sample-solution`.

O detalhe de cada teste está em [Testes](../../docs/testes.md#contrato).
