# Testes

Os testes seguem a pirâmide: muitos testes rápidos sem infraestrutura na base, alguns contra a AWS
emulada no meio e poucos contra a stack implantada no topo. Cada nível tem seu comando e sua
convenção de nome, para o build do dia a dia não depender de deploy.

| Nível | Convenção | Comando | Precisa de | Tempo aproximado |
|---|---|---|---|---|
| Unitário e contrato | `*Test` | `mvn test` | Nada além do JDK | Segundos |
| Integração com Floci | `*IT` | `mvn verify` | Docker | Cerca de 30 s |
| Ponta a ponta | `*E2E` | `pnpm test:e2e` | Docker, Floci do `compose.yaml` no ar e login no Serverless | Cerca de 2 min |

O `mvn verify` roda os dois primeiros níveis nas duas soluções. Os módulos `sample-solution/e2e` e
`receipt-solution/e2e` só entram no build com o perfil `-Pe2e`.

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

```bash
docker compose up -d
pnpm test:e2e
```

O `test:e2e` empacota os módulos sem rodar testes, faz o deploy da `sample-solution` e depois da
`receipt-solution` no stage `local` e executa
`mvn verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e`. Para testar só contra as stacks já
implantadas:

```bash
mvn -B verify -Pe2e -pl sample-solution/e2e,receipt-solution/e2e
```

As propriedades `e2e.endpoint` (padrão `http://localhost:4566`) e `e2e.stage` (padrão `local`) dos
`pom.xml` dos módulos `e2e` apontam para outra stack.

## Limites conhecidos

- A resposta `500` da API não tem teste.
- O suporte do `openapi-request-validator` ao OpenAPI 3.1 é parcial; o contrato atual passou, mas
  recursos específicos do 3.1 podem não ser validados.
- O formato da mensagem do SNS é conferido só no teste de integração, onde há um assinante SQS; a
  stack do exemplo não tem assinante.
- O caminho de falha da `receipt-solution` (execução `FAILED` depois das novas tentativas) não tem
  teste; o exemplo também não tem DLQ para ele.
