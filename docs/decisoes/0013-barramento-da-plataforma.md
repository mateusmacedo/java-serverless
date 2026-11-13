# 0013. Barramento de eventos da plataforma, em stack própria

- Status: aceita
- Data: 2026-11-11

## Contexto

O [ADR 0011](0011-integracao-entre-solucoes-por-eventbridge.md) liga as soluções por regras que a
consumidora declara no barramento da produtora. Isso funciona quando o fluxo tem um só sentido: a
`sample-solution` produz, a `receipt-solution` consome, e a produtora sobe primeiro.

A plataforma de cotação ([PRD de backend](../prds/prd-cotacao-seguros-backend-v2.md), §9) tem duas
soluções que conversam nos dois sentidos. A `quote-solution` pede enriquecimento e cotações; a
`provider-solution` devolve os resultados. Com um barramento por solução, cada uma precisaria do
barramento da outra antes do próprio deploy, um ciclo que só se resolve com deploy em duas passadas.

O próprio ADR 0011 deixou o barramento único como evolução "quando houver mais soluções".

## Decisão

- Uma stack própria, `platform/events` (serviço `platform`), cria o barramento
  `platform-<stage>-events`. Ela não tem funções e sobe antes de qualquer solução que a use.
- `quote-solution` e `provider-solution` publicam nesse barramento e declaram, cada uma no próprio
  `serverless.yml`, regras com filas SQS próprias como alvo. Cada fila tem DLQ.
- Cada solução publica com um `source` próprio: `mmda.quote` e `mmda.provider`. As regras filtram
  sempre por `source` e `detail-type`.
- O contrato entre as soluções continua sendo o evento, documentado no AsyncAPI de cada uma. O
  `detail` leva `eventId`, determinístico quando o mesmo fato pode ser gravado mais de uma vez.
- O descarte por `eventId` vale para eventos de resultado: o consumidor grava o registro de consumo na
  mesma transação do efeito. Pedidos são deduplicados pela identidade do pedido lógico (`workRef` e
  `queryId`), porque a reentrega de um pedido pode chegar com a consulta já em andamento.
- A `sample-solution` e a `receipt-solution` continuam nos próprios barramentos. Migrá-las é evolução
  separada.

## Consequências

- A ordem de deploy passa a ser: `platform/events`, depois as soluções em qualquer ordem. Remover a
  stack do barramento exige remover antes as soluções que têm regras nele.
- Convivem dois padrões no repositório: barramento por solução (ADR 0011) e barramento da plataforma.
- Uma regra com filtro errado entrega eventos a quem não deveria; por isso cada evento produzido tem
  teste de contrato, como o `CompletedRequestContractTest` da receipt.
- O nome do barramento vem de convenção (`platform-<stage>-events`), não de referência entre stacks:
  as soluções não dependem de saídas do CloudFormation da plataforma.

## Alternativas consideradas

- Pedido por fila e resultado por evento: a quote enviaria comandos para a fila da provider. Evita a
  stack nova, mas cria um canal de comando entre soluções e acopla a quote ao schema da fila da outra.
- Barramento por solução com deploy em duas passadas (sem regras, depois com regras): segue o ADR
  0011 ao pé da letra, mas deixa todo ambiente novo parcialmente configurado entre as passadas.
