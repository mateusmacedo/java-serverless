# 0011. Soluções se acionam pelo EventBridge, com a máquina de estado como alvo nativo

- Status: aceita
- Data: 2026-11-10

## Contexto

As soluções compartilham o mesmo ambiente AWS e podem ser escritas em frameworks diferentes. O
processamento de uma deve poder acionar outra sem que as duas dependam do código ou do deploy uma
da outra. A `sample-solution` já publica `RequestCompleted` no próprio barramento.

Há duas formas de iniciar a máquina de estado da solução consumidora a partir de um evento:

- Regra do EventBridge com a máquina de estado como alvo, que a AWS suporta nativamente
  ([Starting a Step Functions workflow in response to events](https://docs.aws.amazon.com/step-functions/latest/dg/tutorial-cloudwatch-events-s3.html)).
  O `serverless-step-functions` declara esse gatilho em `events: - eventBridge:`, com `eventBusName`.
- Regra com uma Lambda como alvo, que chama `StartExecution`.

O README do Floci 2.4.0 lista só SQS, SNS e Lambda como alvos de regra, mas o código da versão
(`EventBridgeInvoker.java`, linha 214) inicia execuções do Step Functions quando o alvo é `states`.

## Decisão

- A solução consumidora declara, no próprio `serverless.yml`, uma regra no barramento da solução
  produtora, com a máquina de estado como alvo. Não há Lambda intermediária.
- O nome do barramento produtor segue a convenção `<service>-<stage>-events`. A produtora não muda
  nem precisa saber quem consome.
- O contrato entre as soluções é o evento: `source`, `detail-type` e o `detail` documentados no
  AsyncAPI da produtora. A consumidora lê só os campos que usa e ignora os desconhecidos.
- A primeira tarefa da consumidora recebe só o `detail` (`InputPath: $.detail`) e é idempotente pela
  chave do evento. O primeiro caso é a `receipt-solution`, que grava o comprovante com
  `attribute_not_exists(requestId)` e devolve o já emitido quando o evento se repete.
- A consumidora publica os próprios eventos no próprio barramento (`ReceiptIssued`), para que outras
  soluções encadeiem da mesma forma.

## Consequências

- A ordem de deploy importa: a produtora sobe antes da consumidora, e a consumidora sai antes da
  produtora, porque a regra vive no barramento da produtora.
- O EventBridge entrega pelo menos uma vez e o alvo nativo não permite escolher o nome da execução;
  por isso a idempotência fica no primeiro passo, e não no início da execução.
- Mudar `source`, `detail-type` ou o `detail` do evento produtor é mudança de contrato. O teste
  `CompletedRequestContractTest` lê o exemplo do AsyncAPI da sample e acusa a quebra.
- O teste ponta a ponta `ReceiptFlowE2E` cobre o caminho inteiro: API Spring, máquina de estado da
  sample, evento, máquina de estado Quarkus, comprovante e `ReceiptIssued`.

## Alternativas consideradas

- Lambda intermediária que chama `StartExecution`: permite usar o `requestId` como nome da execução e
  barrar duplicatas na origem, ao custo de mais uma Lambda, mais um cold start e mais código.
- Barramento único da plataforma, em stack própria, onde todas as soluções publicam e assinam:
  desacopla mais, mas muda a `sample-solution` e cria uma terceira stack para implantar primeiro.
  Fica como evolução quando houver mais soluções.
