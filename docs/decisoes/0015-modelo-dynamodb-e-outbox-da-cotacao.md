# 0015. Uma tabela por solução, outbox publicada por DynamoDB Streams

- Status: aceita
- Data: 2026-11-11

## Contexto

O [PRD de backend](../prds/prd-cotacao-seguros-backend-v2.md) (§10) descreve 18 entidades com
invariantes de unicidade, versões imutáveis, idempotência, outbox e inbox. As soluções do repositório
usam DynamoDB, e as escritas idempotentes já usam `attribute_not_exists`
([ADR 0011](0011-integracao-entre-solucoes-por-eventbridge.md)).

No Floci 2.4.0 foram verificados: `TransactWriteItems` com condição (cancela a transação inteira e
devolve o motivo por item), DynamoDB Streams acionando Lambda e SQS acionando Lambda. Também foi
verificado que o retry padrão do ESM de stream bloqueia o shard: um registro com erro é reinvocado e
os seguintes esperam. No spike do M0, o filtro do ESM, os retries limitados, a divisão do lote, a falha
por registro e o destino de falha em SQS funcionaram como na AWS. A mensagem de falha só aponta o
shard e a faixa de sequência, sem o conteúdo, e a divisão reentrega os registros vizinhos.

## Decisão

- Cada solução tem uma tabela única, com chaves genéricas `pk` e `sk` e o atributo `type` em todo item.
  A quote usa `quote-solution-<stage>-quotes`; a provider usa `provider-solution-<stage>-queries`.
- A listagem de cotações por organização usa o índice `gsi1` (`gsi1pk = ORG#<organizationId>`,
  `gsi1sk = <createdAt>#<quoteId>`), presente no item `META` da cotação. A listagem de ocorrências
  usa o mesmo índice no item `ISSUE#` (`gsi1pk = ISSUES#<organizationId>`,
  `gsi1sk = <lastSeenAt>#<issueId>`), com `type` e `status` em `FilterExpression`.
- Trabalho pendente que a reconciliação precisa varrer usa o índice esparso `gsi2`
  (`gsi2pk` = `ACTIVE_VERSION`, `OUTBOX_PENDING`, `WAITER_RESUMING`, `QUERY_IN_FLIGHT`,
  `QUERY_UNKNOWN`, `INBOX_RECEIVED` ou `INBOX_UNMATCHED`; `gsi2sk` = instante ISO-8601). O atributo é
  removido quando o item sai do estado.
- Unicidade usa `attribute_not_exists(pk)`. Toda mudança que precisa ser atômica com um evento grava,
  na mesma `TransactWriteItems`, um item `OUTBOX#<eventId>`. Ações que se excluem escrevem ou conferem
  o mesmo item: seleção e nova versão escrevem o `META` da cotação; líder e seguidores do
  enriquecimento escrevem ou conferem o `INFLIGHT`.
- A tabela tem stream `NEW_IMAGE`. A Lambda de relay de cada solução (`outbox-relay` na quote,
  `outboxRelay` na provider) recebe só os `INSERT` de itens `OUTBOX` (filtro do ESM pelo atributo `type`), em lotes de até 100, com retries limitados,
  divisão do lote com erro e falha por registro. O registro que esgota os retries vai para uma fila de
  falhas com alarme. A relay publica no barramento da plataforma (ou inicia a execução, no caso
  interno `StartQuoteRequested`) e marca `publishedAt`.
- A publicação é pelo menos uma vez. Consumidores de eventos de resultado descartam repetições pelo
  `eventId`, gravando o registro `CONSUMED` na mesma transação do efeito, nunca antes. Pedidos são
  deduplicados pela identidade do pedido lógico (`workRef` e `queryId`).
- Um republicador agendado, desde o M1 nas duas soluções, republica itens `OUTBOX_PENDING` mais
  antigos que o limite configurado. É o único caminho de volta de um registro descartado pelo stream,
  e lê a tabela, que é a fonte da verdade, não o stream, que guarda os registros por 24 h. A
  reconciliação completa fica no M4.

## Consequências

- Os padrões de acesso ficam documentados em `docs/plataforma-de-cotacao.md`; consultas não previstas
  exigem novo índice, e não um `Scan`.
- O snapshot da cotação é pequeno e fica no próprio item da versão. Respostas brutas de fornecedores
  vão para o S3 da provider, referenciadas por chave.
- Os testes de integração criam as tabelas no Floci com o mesmo esquema do `serverless.yml`.
- Uma `TransactWriteItems` aceita até 100 ações. O fan-out para seguidores fica fora da transação,
  com `eventId` determinístico e `attribute_not_exists`.
- Itens disputados geram `TransactionConflict`, que o SDK não repete e o Floci não emula. O código
  repete com backoff e jitter, e esse caminho é verificado de novo na AWS.

## Alternativas consideradas

- Uma tabela por entidade: deixa os itens mais óbvios, mas multiplica recursos, permissões IAM e
  índices, e as transações passam a cruzar tabelas sem ganho.
- Publicação imediata após o commit, com varredura periódica como único backstop: dispensa o stream,
  mas atrasa a recuperação de falhas até a próxima varredura.

## Emenda (M4)

Nos itens `ISSUE#` das duas soluções, o tipo da ocorrência fica no atributo `issueType`. O atributo
`type` continua sendo o tipo do item, como em todo item da tabela, e vale `ISSUE` nas ocorrências. Por
isso a listagem de ocorrências usa `issueType` e `status` em `FilterExpression`. Nos eventos
`OperationIssueRaised` e `OperationIssueUpdated`, o campo `type` do `detail` continua sendo o tipo da
ocorrência.

O `gsi2` ganhou quatro valores de `gsi2pk` no M4: `WAITER_EXPIRED` e `SUMMARY_PENDING` na quote, e
`FANOUT_PENDING` e `ISSUE_OPEN` na provider.

## Emenda (M5)

A tabela da quote ganhou três itens, e o `ISSUE#` passou a receber também as ocorrências da provider:

- Seleção (`QUOTE#<quoteId>`, `V#<nnnn>#SELECTION`, `type = SELECTION`): uma por versão, gravada com
  `attribute_not_exists` na mesma transação que atualiza o `META` com a mesma condição da nova versão,
  então só uma das duas vence. O `selectionId` é determinístico pela versão.
- Reconciliação manual (`OPERATION#<operationId>`, `META`, `type = OPERATION`): gravada pela API em
  `QUEUED`, com o motivo e quem pediu, na mesma transação do outbox `QueryReconcileRequested`; o
  `issue-projector` grava o resultado do `QueryReconciled` com a condição pela `revision`.
- Consulta conhecida (`QUERYREF#<queryId>`, `META`, `type = QUERYREF`): gravada pelo `issue-projector` só a
  partir de uma ocorrência da provider com `queryId` e `organizationId`; autoriza o `RECONCILE` dessa
  organização.
- `ISSUE#` projetado: a ocorrência da provider, com `source = provider`, o `type` do evento em
  `issueType`, o `projectedAt` e a condição pela `revision`, na mesma transação do `CONSUMED#<eventId>`.
  Ela usa o `gsi1` da organização, como as da quote, e nunca o `gsi2`.

A listagem de cotações usa o `gsi1` (`ORG#<organizationId>`) previsto acima, e o resumo do `META` que ela
lê passou a só avançar dentro da versão. As duas listagens paginam com o `gsi1sk` do último item como
cursor.

Valores de `gsi2pk` depois do M5, todos com `gsi2sk` = instante ISO-8601 e removidos quando o item sai do
estado:

| Solução | Valor | Item | Registrado em |
|---|---|---|---|
| quote | `ACTIVE_VERSION` | Versão não terminal | Decisão |
| quote | `OUTBOX_PENDING` | Outbox ainda não publicado | Decisão |
| quote | `WAITER_RESUMING` | Waiter em retomada | Decisão |
| quote | `WAITER_EXPIRED` | Waiter expirado à espera da conferência da entrega | Emenda (M4) |
| quote | `SUMMARY_PENDING` | Versão consolidada cujo resumo do `META` ainda não foi conferido | Emenda (M4) |
| quote | `ISSUE_OPEN` | Ocorrência da quote que se resolve sozinha, ainda aberta: só a `STUCK_VERSION` | Esta emenda |
| provider | `QUERY_IN_FLIGHT` | Consulta em voo | Decisão |
| provider | `QUERY_UNKNOWN` | Consulta sem resposta depois do envio | Decisão |
| provider | `INBOX_RECEIVED` | Callback recebido ainda não processado | Decisão |
| provider | `INBOX_UNMATCHED` | Callback sem correlação | Decisão |
| provider | `OUTBOX_PENDING` | Outbox ainda não publicado | Decisão |
| provider | `FANOUT_PENDING` | Fan-out do líder ainda não concluído | Emenda (M4) |
| provider | `ISSUE_OPEN` | Ocorrência que se resolve sozinha, ainda aberta (`UNKNOWN_QUERY`, `UNMATCHED_CALLBACK`, `STALE_OUTBOX`) | Emenda (M4) |

O `ISSUE_OPEN` tem o mesmo nome nas duas soluções, mas cada tabela tem o seu índice: as ocorrências
projetadas da provider na quote nunca entram no `ISSUE_OPEN` da quote.
