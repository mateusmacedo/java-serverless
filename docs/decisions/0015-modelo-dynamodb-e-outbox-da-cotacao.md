# 0015. Uma tabela por solução, outbox publicada por DynamoDB Streams

- Status: aceita
- Data: 2026-11-11

## Contexto

O [PRD de backend](../prds/PRD_Cotacao_Seguros_Backend_v2.md) (§10) descreve 18 entidades com
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

- Os padrões de acesso ficam documentados em `docs/quotation-platform.md`; consultas não previstas
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
