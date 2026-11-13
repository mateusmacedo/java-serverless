# Fluxos

Como uma requisição percorre a `sample-solution` e como o resultado aciona a `receipt-solution`. Os
componentes estão descritos em [Arquitetura](arquitetura.md); os formatos das mensagens, no
[AsyncAPI da sample](../contracts/sample-solution/asyncapi.yaml) e no [da receipt](../contracts/receipt-solution/asyncapi.yaml).

## Máquina de estado `request-flow`

Definida em `stepFunctions.stateMachines.requestFlow` no `sample-solution/serverless.yml`.

```plantuml
@startuml estados-request-flow
!include diagramas/tema-plataforma.iuml
hide empty description
title Máquina de estado request-flow

state Validate <<ativo>>
state Persist <<ativo>>
state Notify <<ativo>>
state SendToDeadLetterQueue <<erro>>
state RequestFailed <<erro>>

[*] --> Validate
Validate --> Persist
Persist --> Notify
Notify --> [*]
Validate -[$BORDA_ERRO]-> SendToDeadLetterQueue : erro, States.ALL
Persist -[$BORDA_ERRO]-> SendToDeadLetterQueue : erro, States.ALL
Notify -[$BORDA_ERRO]-> SendToDeadLetterQueue : erro, States.ALL
SendToDeadLetterQueue -[$BORDA_ERRO]-> RequestFailed
RequestFailed -[$BORDA_ERRO]-> [*]

$legenda_erros()
@enduml
```

| Estado                  | Tipo                                     | O que faz                                                               |
| ----------------------- | ---------------------------------------- | ----------------------------------------------------------------------- |
| `Validate`              | Task, Lambda `validate`                  | Rejeita payload vazio ou acima do limite; assina o conteúdo com HMAC    |
| `Persist`               | Task, Lambda `persist`                   | Grava o resultado no DynamoDB                                           |
| `Notify`                | Task, Lambda `notify`                    | Publica no SNS e emite `RequestCompleted` no EventBridge                |
| `SendToDeadLetterQueue` | Task, `arn:aws:states:::sqs:sendMessage` | Envia a entrada do estado que falhou, com o erro em `error`, para a DLQ |
| `RequestFailed`         | Fail                                     | Encerra a execução como `FAILED` com o erro `RequestFailed`             |

Os três passos de negócio têm o mesmo `Catch`: qualquer erro (`States.ALL`) é gravado em `$.error`
e a execução segue para `SendToDeadLetterQueue`.

## Caminho de sucesso

```plantuml
@startuml sequencia-sucesso
!include diagramas/tema-plataforma.iuml
hide stereotype
title Caminho de sucesso
autonumber
actor "Cliente" as C <<ator>>
participant "$I_API\nAPI Gateway" as GW <<entrada>>
participant "$I_LAMBDA\nLambda api" as API <<aplicacao>>
participant "$I_S3\nS3" as S3 <<infra>>
participant "$I_MAQUINA\nStep Functions" as SFN <<aplicacao>>
participant "$I_LAMBDA\nLambda validate" as V <<aplicacao>>
participant "$I_SEGREDO\nSSM e Secrets Manager" as CFG <<infra>>
participant "$I_LAMBDA\nLambda persist" as P <<aplicacao>>
participant "$I_DYNAMODB\nDynamoDB" as DDB <<infra>>
participant "$I_LAMBDA\nLambda notify" as N <<aplicacao>>
participant "$I_SNS\nSNS" as SNS <<infra>>
participant "$I_EVENTBRIDGE\nEventBridge" as EB <<infra>>
participant "$I_SQS\nSQS audit" as AQ <<infra>>

C -> GW : POST /requests com JSON
GW -> API : evento proxy
API -> S3 : PutObject em requests/requestId.json
API -> SFN : StartExecution com requestId, s3Key e attempt 1
API --> C : 202 com requestId e executionArn
SFN -> V : RequestContext
V -> CFG : GetParameter e GetSecretValue
V -> S3 : GetObject
V --> SFN : RequestContext com signature e payloadBytes
SFN -> P : RequestContext
P -> DDB : PutItem
P --> SFN : RequestContext
SFN -> N : RequestContext
N -> SNS : Publish
N -> EB : PutEvents RequestCompleted
EB -> AQ : regra entrega o evento
N --> SFN : RequestContext

$legenda_camadas()
@enduml
```

O cliente recebe `202` assim que a execução é iniciada; o processamento continua de forma
assíncrona. O nome da execução é o `requestId` (e `requestId-attempt` nos reprocessamentos), o que
liga cada execução ao objeto no S3 e ao item no DynamoDB. O payload fica no S3 e só a chave trafega
na máquina de estado
([ADR 0006](decisoes/0006-payload-no-s3.md)).

## Caminho de falha

```plantuml
@startuml sequencia-falha
!include diagramas/tema-plataforma.iuml
hide stereotype
title Caminho de falha
autonumber
participant "$I_MAQUINA\nStep Functions" as SFN <<aplicacao>>
participant "$I_LAMBDA\nLambda validate" as V <<aplicacao>>
participant "$I_DLQ\nSQS dlq" as DLQ <<infra>>

SFN -> V : RequestContext
V --> SFN : <color:$BORDA_ERRO>erro InvalidRequestException</color>
note over SFN : Catch States.ALL grava o erro em $.error
SFN -[$BORDA_ERRO]> DLQ : sqs sendMessage com a entrada e o erro
note over SFN : RequestFailed encerra a execução como FAILED

$legenda_erros()
@enduml
```

Validado no Floci com um payload de 5012 bytes: o `validate` falhou com
`Payload of 5012 bytes exceeds the limit of 4096 bytes` e a mensagem chegou à DLQ com o campo
`error` (`Error` com o tipo da exceção e `Cause` com o JSON de erro do runtime da Lambda).

## Reprocessamento

```plantuml
@startuml sequencia-reprocessamento
!include diagramas/tema-plataforma.iuml
hide stereotype
title Reprocessamento
autonumber
participant "$I_AGENDADOR\nEventBridge Scheduler" as SCH <<infra>>
participant "$I_LAMBDA\nLambda reprocess" as R <<aplicacao>>
participant "$I_DLQ\nSQS dlq" as DLQ <<infra>>
participant "$I_MAQUINA\nStep Functions" as SFN <<aplicacao>>

SCH -> R : maxMessages 10, a cada 5 minutos
R -> DLQ : GetQueueUrl e ReceiveMessage, até 10 mensagens
loop para cada mensagem
    alt $COR_DOMINIO attempt abaixo de maxAttempts, padrão 3
        R -> SFN : StartExecution com attempt + 1, nome requestId-attempt
    else $COR_ERRO limite de tentativas atingido
        note over R : descarta e registra um aviso no log
    end
    R -> DLQ : DeleteMessage
end
R --> SCH : restarted e discarded

$legenda_erros()
@enduml
```

A nova execução recomeça do `Validate`, com `signature` e `payloadBytes` limpos. Um payload que
sempre falha é reiniciado duas vezes e descartado na terceira tentativa
([ADR 0007](decisoes/0007-dlq-e-reprocessamento.md)).

## Integração com a receipt-solution

A `receipt-solution`, em Quarkus, reage ao mesmo `RequestCompleted`. Uma regra que ela cria no
barramento da `sample-solution` inicia a máquina de estado `receipt-flow` direto, sem Lambda no meio
([ADR 0011](decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)).

```plantuml
@startuml sequencia-integracao-receipt
!include diagramas/tema-plataforma.iuml
hide stereotype
title Integração com a receipt-solution
autonumber
participant "$I_LAMBDA\nLambda notify\n(sample, Spring)" as N <<aplicacao>>
participant "$I_EVENTBRIDGE\nEventBridge\nsample events" as EB <<infra>>
participant "$I_MAQUINA\nStep Functions\nreceipt-flow" as RSFN <<aplicacao>>
participant "$I_LAMBDA\nLambda issue\n(receipt, Quarkus)" as I <<aplicacao>>
participant "$I_DYNAMODB\nDynamoDB\nreceipts" as RDDB <<infra>>
participant "$I_LAMBDA\nLambda announce\n(receipt, Quarkus)" as A <<aplicacao>>
participant "$I_EVENTBRIDGE\nEventBridge\nreceipt events" as REB <<infra>>

N -> EB : PutEvents RequestCompleted
EB -> RSFN : regra da receipt-solution inicia a execução com o evento
RSFN -> I : detail do evento (InputPath $.detail)
I -> RDDB : PutItem se requestId ainda não existe
alt evento repetido
    RDDB --> I : ConditionalCheckFailed
    I -> RDDB : GetItem com leitura consistente
end
I --> RSFN : Receipt
RSFN -> A : Receipt
A -> REB : PutEvents ReceiptIssued
A --> RSFN : Receipt

$legenda_camadas()
@enduml
```

O EventBridge entrega cada evento pelo menos uma vez, e o alvo nativo não deixa escolher o nome da
execução. Por isso o `issue` é idempotente: um evento repetido devolve o comprovante já emitido, com o
mesmo `issuedAt`. Os dois passos têm 2 novas tentativas, com intervalo inicial de 2 segundos. A
`sample-solution` não sabe que a `receipt-solution` existe: o contrato entre as duas é o evento.

## API da receipt-solution

```plantuml
@startuml sequencia-api-receipt
!include diagramas/tema-plataforma.iuml
hide stereotype
title API da receipt-solution
autonumber
actor "Cliente" as C <<ator>>
participant "$I_API\nAPI Gateway\nda receipt" as GW <<entrada>>
participant "$I_LAMBDA\nLambda api\n(Quarkus REST)" as API <<aplicacao>>
participant "$I_DYNAMODB\nDynamoDB\nreceipts" as RDDB <<infra>>
participant "$I_MAQUINA\nStep Functions\nreceipt-flow" as RSFN <<aplicacao>>

C -> GW : GET /receipts/requestId
GW -> API : evento proxy
API -> RDDB : GetItem com leitura consistente
API --> C : 200 com o comprovante ou 404
C -> GW : POST /receipts com requestId, signature e payloadBytes
GW -> API : evento proxy
API -> RSFN : StartExecution com detail igual ao corpo
API --> C : 202 com requestId e executionArn
note over RSFN : segue igual ao caminho do evento: issue e depois announce

$legenda_camadas()
@enduml
```

A emissão manual entra na mesma máquina e no mesmo formato do evento, então reaproveita o `issue`
idempotente: emitir de novo para um `requestId` que já tem comprovante devolve o comprovante
existente e publica `ReceiptIssued` mais uma vez. Os contratos estão no
[OpenAPI da receipt](../contracts/receipt-solution/openapi.yaml).

## Onde cada erro termina

| Situação                                                            | Resultado                                                             | Onde observar                                        |
| ------------------------------------------------------------------- | --------------------------------------------------------------------- | ---------------------------------------------------- |
| `Content-Type` diferente de `application/json`                      | `415` sem corpo                                                       | Resposta HTTP                                        |
| Corpo ausente                                                       | `400` sem corpo                                                       | Resposta HTTP                                        |
| Método não mapeado, como `GET /requests`                            | `403` com `Missing Authentication Token`, devolvido pelo API Gateway  | Resposta HTTP                                        |
| Falha ao gravar no S3 ou iniciar a execução                         | `500` (não reproduzido nos testes)                                    | Resposta HTTP e log da Lambda `api`                  |
| Payload vazio ou acima de 4096 bytes                                | Execução `FAILED` e mensagem na DLQ                                   | Histórico da execução e fila `dlq`                   |
| Erro em `persist` ou `notify`                                       | Execução `FAILED` e mensagem na DLQ                                   | Histórico da execução e fila `dlq`                   |
| Falha repetida após 3 tentativas                                    | Mensagem descartada                                                   | Log da Lambda `reprocess` (`Discarding request ...`) |
| Erro em `issue` ou `announce` (receipt-solution)                    | 2 novas tentativas; depois, execução `FAILED`, sem DLQ no exemplo     | Histórico da execução `receipt-flow`                 |
| `RequestCompleted` entregue mais de uma vez                         | Mesmo comprovante; o `announce` pode publicar `ReceiptIssued` de novo | Tabela `receipts` e barramento da receipt-solution   |
| `GET /receipts/{requestId}` sem comprovante                         | `404` sem corpo                                                       | Resposta HTTP                                        |
| `POST /receipts` com campo ausente ou `payloadBytes` menor que 1    | `400` com a lista de violações, sem iniciar execução                  | Resposta HTTP                                        |
| `POST /receipts` com `Content-Type` diferente de `application/json` | `415` sem corpo                                                       | Resposta HTTP                                        |
