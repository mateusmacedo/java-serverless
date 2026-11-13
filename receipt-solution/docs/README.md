# receipt-solution

Solução de comprovantes em Quarkus: para cada `RequestCompleted` da
[`sample-solution`](../../sample-solution/docs/README.md), emite um comprovante idempotente por `requestId`,
grava no DynamoDB e anuncia `ReceiptIssued` no barramento próprio. Uma API HTTP consulta o comprovante e
permite a emissão manual pela mesma máquina de estado. É o primeiro caso de integração entre soluções por
EventBridge ([ADR 0011](../../docs/decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)). O contexto está
em [Arquitetura](../../docs/arquitetura.md), os caminhos em
[Fluxos § Integração com a receipt-solution](../../docs/fluxos.md#integração-com-a-receipt-solution) e a subida
das Lambdas Quarkus no [Guia do código](../../docs/guia-do-codigo.md#como-as-lambdas-da-receipt-solution-sobem-quarkus).
O parent Maven está em [Plataforma](../../platform/docs/README.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Funções Lambda e papéis](#funções-lambda-e-papéis)
- [Máquina de estado](#máquina-de-estado)
- [API HTTP](#api-http)
- [Eventos publicados e consumidos](#eventos-publicados-e-consumidos)
- [Persistência](#persistência)
- [Filas e DLQ](#filas-e-dlq)
- [Segurança e IAM](#segurança-e-iam)
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
| Iniciar a emissão a cada `RequestCompleted`, sem Lambda intermediária | Regra `eventBridge` da `receiptFlow` em [`serverless.yml`](../serverless.yml), no barramento `sample-solution-<stage>-events` |
| Emitir o comprovante uma só vez por `requestId`, devolvendo o existente na repetição | [`IssueReceiptHandler`](../functions/src/main/java/com/mmda/serverless/receipt/functions/IssueReceiptHandler.java) |
| Anunciar `ReceiptIssued` para outras soluções encadearem | [`AnnounceReceiptHandler`](../functions/src/main/java/com/mmda/serverless/receipt/functions/AnnounceReceiptHandler.java) |
| Consultar o comprovante e emitir manualmente | [`ReceiptResource`](../api/src/main/java/com/mmda/serverless/receipt/api/ReceiptResource.java) |
| Ler só os campos do evento que usa e ignorar os desconhecidos | [`CompletedRequest`](../functions/src/main/java/com/mmda/serverless/receipt/functions/CompletedRequest.java) (`@JsonIgnoreProperties(ignoreUnknown = true)`) |

A `sample-solution` não sabe que esta existe: o contrato entre as duas é o evento.

## Funções Lambda e papéis

Runtime `java21` em modo JVM ([ADR 0004](../../docs/decisoes/0004-sem-graalvm-native.md)), 1769 MB, handler
`io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler::handleRequest` nas três. Os SDKs e a entrada e a saída
de cada uma estão em [Arquitetura § Lambdas](../../docs/arquitetura.md#lambdas).

| Função (`receipt-solution-<stage>-…`) | Pacote | Timeout | Gatilho | Papel |
|---|---|---|---|---|
| `api` | `api/target/receipt-api-lambda.zip` (`quarkus-amazon-lambda-rest`) | 29 s | API Gateway, `GET /receipts/{requestId}` e `POST /receipts` | Entrada HTTP |
| `issue` | `functions/target/receipt-functions-lambda.zip` (`quarkus-amazon-lambda`) | 30 s | Estado `Issue` | Emissão idempotente |
| `announce` | mesmo zip | 30 s | Estado `Announce` | Publicação |

`issue` e `announce` dividem o zip; cada Lambda escolhe o handler pela variável `QUARKUS_LAMBDA_HANDLER`, que
bate com o `@Named` da classe (o `application.properties` usa `issue` como padrão). O nome
`<artifactId>-lambda.zip` vem do `quarkus-parent`, porque as duas extensões gerariam o mesmo `function.zip`
([Arquitetura § Empacotamento](../../docs/arquitetura.md#empacotamento)).

## Máquina de estado

`receipt-solution-<stage>-receipt-flow` (Standard): `Issue`, com `InputPath: $.detail`, e depois `Announce`.
Os dois estados têm o mesmo `Retry` de `States.ALL`: intervalo de 2 s, `BackoffRate` 2 e até 2 novas
tentativas. Não há `Catch` nem DLQ: um erro que persiste termina a execução como `FAILED`, visível só no
histórico. Desenho em [`estados-receipt-flow.puml`](diagramas/estados-receipt-flow.puml).

As execuções não têm nome escolhido: o alvo nativo do EventBridge não permite, e o `POST /receipts` também não
passa nome. Por isso a idempotência fica no primeiro passo, e não no início da execução (ADR 0011).

## API HTTP

Contrato: [`contracts/receipt-solution/openapi.yaml`](../../contracts/receipt-solution/openapi.yaml) (OpenAPI
3.1), conferido pelo `ReceiptApiContractTest`. API REST `<stage>-receipt-solution`, sem autorizador.

| Método | Caminho | Recurso | Sucesso | Falhas |
|---|---|---|---|---|
| `GET` | `/receipts/{requestId}` | `ReceiptResource.find`: `GetItem` com leitura consistente | `200` com o `Receipt` | `404` sem corpo |
| `POST` | `/receipts` | `ReceiptResource.issue`: `StartExecution` com `{"detail": <corpo>}` | `202` com `requestId` e `executionArn` | `400` com a lista de violações (`requestId` ou `signature` em branco, `payloadBytes` ausente ou menor que 1), `415` sem corpo |

O corpo do `POST` tem os mesmos campos que o `issue` lê do `detail` do evento, então a emissão manual reusa a
máquina e a idempotência. Os caminhos estão em [Fluxos § API da receipt-solution](../../docs/fluxos.md#api-da-receipt-solution).

## Eventos publicados e consumidos

Formatos no [AsyncAPI da receipt](../../contracts/receipt-solution/asyncapi.yaml), que referencia o
`RequestCompleted` do [AsyncAPI da sample](../../contracts/sample-solution/asyncapi.yaml) sem copiar.

| Evento | Direção | Barramento | `source` / `detail-type` | Quem trata |
|---|---|---|---|---|
| `RequestCompleted` | Consumido | `sample-solution-<stage>-events` | `mmda.sample` / `RequestCompleted` | Regra gerada pelo `serverless-step-functions` inicia a `receipt-flow`; o `issue` lê `requestId`, `signature` e `payloadBytes` |
| `ReceiptIssued` | Publicado pelo `announce` | `receipt-solution-<stage>-events` | `mmda.receipt` / `ReceiptIssued` | Nenhuma regra no repositório; o `ReceiptFlowE2E` cria uma regra e uma fila temporárias para conferir |

O `detail` do `ReceiptIssued` é o próprio `Receipt` (`requestId`, `signature`, `payloadBytes`, `issuedAt`). O
EventBridge entrega pelo menos uma vez: um `RequestCompleted` repetido devolve o mesmo comprovante, mas publica
`ReceiptIssued` de novo. O `announce` falha quando o `PutEvents` volta com `FailedEntryCount > 0`.

## Persistência

| Tabela | Chave | Atributos | Escrita | Leitura |
|---|---|---|---|---|
| `receipt-solution-<stage>-receipts` (`PAY_PER_REQUEST`) | `requestId` (S, HASH) | `signature` (S), `payloadBytes` (N), `issuedAt` (S, ISO-8601 UTC) | `issue`: `PutItem` com `attribute_not_exists(requestId)` | `issue` (`GetItem` consistente na repetição) e `api` (`GetItem` consistente) |

Sem índice, stream nem TTL; o item nunca é atualizado nem apagado pelo código. O mesmo formato é lido por dois
records `Receipt`, um no `receipt-api` e outro no `receipt-functions`, com os mesmos nomes de atributos. Modelo
em [`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml) e ciclo de vida em
[`estados-comprovante.puml`](diagramas/estados-comprovante.puml).

## Filas e DLQ

A solução não tem fila nem DLQ. As falhas transitórias são absorvidas pelo `Retry` da máquina; uma falha que
persiste deixa a execução `FAILED`, sem reprocessamento automático
([Testes § Limites conhecidos](../../docs/testes.md#limites-conhecidos)).

Para reanunciar um comprovante cuja execução falhou no `Announce`, emita de novo pelo `POST /receipts` com o
mesmo `requestId`: o `issue` devolve o item já gravado (os demais campos do corpo são ignorados nesse caso) e o
`announce` publica outra vez.

## Segurança e IAM

- **API:** sem autorizador nem chave de API. O `POST /receipts` aceita qualquer `requestId`, mesmo um que a
  `sample-solution` nunca processou; não há conferência contra a tabela da sample.
- **Papel das Lambdas:** um só papel para as três funções, gerado a partir de `provider.iam.role.statements`.
- **Papéis gerados por plugin:** o da `receipt-flow` e o que deixa o EventBridge iniciar a máquina vêm do
  `serverless-step-functions`, junto com a regra.
- **Floci:** não aplica as políticas por padrão.

| Ação | Recurso | Quem usa |
|---|---|---|
| `dynamodb:PutItem`, `dynamodb:GetItem` | Tabela `receipts` | `issue` (Put e Get), `api` (Get) |
| `events:PutEvents` | Barramento `receipt-solution-<stage>-events` | `announce` |
| `states:StartExecution` | ARN da `receipt-flow` montado por nome (um `Ref` criaria ciclo com as Lambdas) | `api` |

## Observabilidade e alarmes

Sem alarmes, métricas próprias nem rastreamento, e sem `logRetentionInDays` no `serverless.yml`.

| Sinal | Onde ver |
|---|---|
| Execução `FAILED` depois das novas tentativas | Histórico da `receipt-flow` (Step Functions ou console do Floci em `localhost:4500`) |
| Evento repetido | Mesmo `issuedAt` na tabela e mais de um `ReceiptIssued` com o mesmo `requestId` |
| Logs das funções | `/aws/lambda/receipt-solution-<stage>-<função>` |

## Configuração

Os `params` existem só para o stage `local`; os valores no Floci estão em
[Arquitetura § Configuração](../../docs/arquitetura.md#configuração).

| Param (`params.local`) | Valor | Variável |
|---|---|---|
| `awsEndpointUrl` | `http://floci:4566` | `AWS_ENDPOINT_URL`, lida pela cadeia padrão do SDK |

| Variável | Propriedade | Padrão | Usada por |
|---|---|---|---|
| `TABLE_NAME` | `receipt.table-name` | obrigatória | `api`, `issue` |
| `EVENT_BUS_NAME` | `receipt.event-bus-name` | obrigatória no `receipt-functions` | `announce` |
| `STATE_MACHINE_ARN` | `receipt.state-machine-arn` | obrigatória no `receipt-api` | `api` |
| `QUARKUS_LAMBDA_HANDLER` | `quarkus.lambda.handler` | `issue` | `issue` e `announce` |

As propriedades são lidas por `ReceiptApiConfig` e `ReceiptConfig` (`@ConfigMapping(prefix = "receipt")`). Os
clients vêm das extensões do Quarkus Amazon Services com `sync-client.type=aws-crt`, o mesmo client HTTP do
`spring-aws` ([ADR 0010](../../docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md)), e os Dev Services
ficam desligados (`quarkus.devservices.enabled=false`).

## Rodar localmente no Floci

A `sample-solution` precisa estar implantada antes, porque a regra desta fica no barramento dela:

```bash
mvn -B -q package -DskipTests -pl receipt-solution/api,receipt-solution/functions -am
pnpm deploy:sample   # se ainda não estiver implantada
pnpm deploy:receipt
```

Os `curl` da API estão em [README § Fluxo de exemplo](../../README.md#fluxo-de-exemplo). Na remoção, a
`receipt-solution` sai antes da sample ([README § Remover a implantação do Floci](../../README.md#remover-a-implantação-do-floci)).

## Implantar na AWS

Não preparado; o [guia do stage `dev`](../../docs/implantacao-aws-dev.md) cobre só a plataforma de cotação.
Antes de um deploy numa conta AWS, falta declarar `params` para o stage (hoje só há `params.local`, então
`${param:awsEndpointUrl}` fica sem valor) e implantar a `sample-solution` no mesmo stage antes desta. A
topologia alvo está em [`implantacao-aws.puml`](diagramas/implantacao-aws.puml); nada dela foi executado na
AWS.

## Testes

Unitários e de contrato no `receipt-api` e no `receipt-functions` (`*Test`, `mvn test`; o
`CompletedRequestContractTest` lê o exemplo do `RequestCompleted` copiado do AsyncAPI da sample), integração com
o Floci (`ReceiptFunctionsIT` e `ReceiptApiFlociIT`, `mvn verify`) e ponta a ponta (`ReceiptFlowE2E`, perfil
`-Pe2e`, com as duas stacks implantadas). Para rodar só esta solução:

```bash
mvn -B verify -pl receipt-solution/api,receipt-solution/functions -am
```

O que cada classe cobre está em [Testes](../../docs/testes.md): seções
[Unitários](../../docs/testes.md#unitários), [Contrato](../../docs/testes.md#contrato),
[Integração com Floci](../../docs/testes.md#integração-com-floci),
[Ponta a ponta](../../docs/testes.md#ponta-a-ponta) e [Limites conhecidos](../../docs/testes.md#limites-conhecidos).

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas), com o tema único
[`tema-plataforma.iuml`](../../docs/diagramas/tema-plataforma.iuml). Para renderizar só esta solução, na raiz:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/receipt-solution 'receipt-solution/docs/diagramas/*.puml'
```

| Diagrama | O que mostra e como ler |
|---|---|
| [`c4-conteiner.puml`](diagramas/c4-conteiner.puml) | Contêineres (C4 nível 2): API, Lambdas, `receipt-flow`, tabela e barramento, com a `sample-solution` como origem do evento. Tracejado roxo é assíncrono |
| [`c4-componente-api.puml`](diagramas/c4-componente-api.puml) | Dentro da Lambda `api`: `QuarkusStreamHandler`, `ReceiptResource`, validação e clients |
| [`c4-componente-functions.puml`](diagramas/c4-componente-functions.puml) | O zip de `issue` e `announce`: escolha do handler por `QUARKUS_LAMBDA_HANDLER`, relógio e clients |
| [`sequencia-emissao-por-evento.puml`](diagramas/sequencia-emissao-por-evento.puml) | `RequestCompleted` até o `ReceiptIssued`, com o ramo do evento repetido (laranja) e o da falha sem DLQ (vermelho) |
| [`sequencia-emissao-manual.puml`](diagramas/sequencia-emissao-manual.puml) | `POST /receipts`: validação (`400`, `415`) e início da mesma máquina |
| [`sequencia-consulta.puml`](diagramas/sequencia-consulta.puml) | `GET /receipts/{requestId}`: `200` ou `404` |
| [`estados-receipt-flow.puml`](diagramas/estados-receipt-flow.puml) | Estados da máquina; os laços são o `Retry`, e a falha é implícita (não há estado `Fail`) |
| [`estados-comprovante.puml`](diagramas/estados-comprovante.puml) | Ciclo de vida do item (não há enum no código): emitido uma vez, anunciado uma ou mais vezes |
| [`modelo-dynamodb.puml`](diagramas/modelo-dynamodb.puml) | Item da tabela `receipts` e as duas formas de acesso |
| [`implantacao-floci.puml`](diagramas/implantacao-floci.puml) | Stage `local`: a stack da receipt, a regra no barramento da sample e os contêineres das Lambdas |
| [`implantacao-aws.puml`](diagramas/implantacao-aws.puml) | Topologia alvo na AWS, com o que falta preparar em vermelho |
| [`classes-api.puml`](diagramas/classes-api.puml) | Classes do pacote `com.mmda.serverless.receipt.api` |
| [`classes-functions.puml`](diagramas/classes-functions.puml) | Classes do pacote `com.mmda.serverless.receipt.functions` |
| [`casos-de-uso.puml`](diagramas/casos-de-uso.puml) | Cliente, `sample-solution` e o consumidor futuro do `ReceiptIssued` |

## Divergências conhecidas

1. **Falha no `Announce`.** Depois das novas tentativas, a execução fica `FAILED` com o comprovante já gravado e
   sem `ReceiptIssued`. Nada reprocessa sozinho; a saída é a reemissão manual descrita em [Filas e DLQ](#filas-e-dlq).
2. **Emissão manual sem conferência de origem.** O `POST /receipts` emite comprovante para qualquer `requestId`
   válido no formato, e a primeira emissão fixa `signature` e `payloadBytes`: um `RequestCompleted` que chegue
   depois para o mesmo `requestId` devolve o comprovante manual, e não um com os dados do evento.
