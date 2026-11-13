# 0017. Remoção das soluções iniciais `sample-solution` e `receipt-solution`

- Status: aceita
- Data: 2026-11-13

## Contexto

A versão 1.0.0 trouxe duas soluções de referência: a `sample-solution`, em Spring, que valida, persiste e
notifica uma requisição com DLQ e reprocessamento, e a `receipt-solution`, em Quarkus, que emite um
comprovante a partir do `RequestCompleted` da primeira. Elas serviram para provar o ambiente: os parents
por framework (ADR 0010), a integração por EventBridge (ADR 0011) e o pacote da Lambda com os nativos Linux
do `aws-crt` (ADR 0012).

Depois disso, a plataforma de cotação de seguros (`quote-solution`, `provider-solution`, `platform/events`
e `backoffice`) passou a cobrir os mesmos padrões com um caso de uso real e mais completo: os dois
frameworks, o barramento da plataforma (ADR 0013), o task token (ADR 0014), a outbox por DynamoDB Streams
(ADR 0015), DLQs com reprocessamento, reconciliação, autenticação e um frontend. Nenhum módulo da cotação
depende das soluções iniciais.

Manter as duas custava caro: uma stack a mais em cada `deploy:local`, um segundo padrão de barramento
(por solução) convivendo com o da plataforma, testes e contratos próprios e uma documentação paralela
(`docs/fluxos.md` e `docs/guia-do-codigo.md`) que não descrevia o que o projeto quer demonstrar.

## Decisão

- Remover a `sample-solution`, a `receipt-solution` e os contratos delas em `contracts/`.
- Tirar os módulos do `pom.xml` raiz, os scripts `deploy:sample` e `deploy:receipt` do `package.json` e a
  dependência `serverless-offline`, que só a `sample-solution` usava.
- Remover `docs/fluxos.md` e `docs/guia-do-codigo.md` e reduzir `docs/arquitetura.md` ao que vale para
  todas as soluções; a cotação continua documentada em `docs/cotacao.md`, no desenho técnico e no README
  de cada solução.
- Manter os ADRs anteriores sem alteração, como histórico: os que citam a sample ou a receipt descrevem
  o contexto em que foram tomados.
- Publicar a remoção como versão maior (2.0.0), porque APIs, eventos e stacks deixam de existir.

## Consequências

- A plataforma de cotação é a única solução de demonstração; o `deploy:local` implanta três stacks:
  `platform/events`, `provider-solution` e `quote-solution`.
- Fica um único padrão de barramento, o da plataforma (ADR 0013). O padrão de barramento por solução do
  ADR 0011 deixa de ter exemplo no código.
- Os ADRs 0002, 0006, 0007 e 0008 descrevem decisões tomadas para a `sample-solution`. As que a cotação
  também aplica, como Spring Cloud Function na provider e DLQ com reprocessamento, continuam valendo por
  ela; a do `serverless-offline` (ADR 0008) fica só como histórico.
- O código das duas soluções continua acessível pela tag `v1.0.0`. Quem tiver as stacks delas implantadas
  num Floci da versão 1.0.0 deve removê-las com os comandos do README daquela tag, antes ou depois de
  atualizar.

## Alternativas consideradas

- Manter as duas soluções como exemplo mínimo: preserva um ponto de entrada mais simples, mas mantém o
  custo de deploy, testes e documentação paralela, e dois padrões de barramento.
- Mover as duas para uma pasta de exemplos fora do build Maven: tira o custo do build, mas deixa código e
  documentação sem teste, que envelhecem sem que ninguém perceba.
- Reescrever `docs/fluxos.md` e `docs/guia-do-codigo.md` para a cotação: mantém o guia didático, mas repete
  o desenho técnico e os READMEs das soluções, e as cópias tendem a divergir.
