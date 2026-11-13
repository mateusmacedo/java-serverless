# 0014. Cotação em duas soluções, com o task token restrito à quote-solution

- Status: aceita
- Data: 2026-11-11

## Contexto

O [PRD de backend](../prds/prd-cotacao-seguros-backend-v2.md) pede uma máquina de estado Standard que
espera resultados externos (enriquecimento e seguradoras) por task token. O repositório usa um
framework por solução ([ADR 0010](0010-raiz-neutra-e-parents-por-framework.md)), e as soluções só
conversam por eventos ([ADR 0011](0011-integracao-entre-solucoes-por-eventbridge.md) e
[ADR 0013](0013-barramento-da-plataforma.md)).

A única meta de latência do PRD é a da API de produto (§12: POST p95 até 1 s, GET p95 até 500 ms). No
Floci, as Lambdas Quarkus subiram em cerca de 0,8 s e as Spring em 1,7 a 1,9 s (ADR 0010). O trabalho
com fornecedores é assíncrono, com prazo de até 90 s por seguradora, e concentra a coordenação mais
complexa: liderança, lease, fencing, quota, inbox e reconciliação.

## Decisão

- `quote-solution`, em Quarkus: API de produto, máquina de estado principal, regras de elegibilidade e
  de seleção de seguradoras, consolidação, seleção de oferta, outbox, waiters, resumer e reconciliação
  de cotações.
- `provider-solution`, em Spring: workers de enriquecimento e de seguradoras, reuso com liderança,
  quota por provedor, API de callbacks com inbox, reconciliação de consultas e os simuladores de
  fornecedores.
- Os passos que esperam a outra solução usam `arn:aws:states:::lambda:invoke.waitForTaskToken` com a
  Lambda `dispatch` da quote. Ela grava, numa transação, o waiter com o token cifrado e um evento de
  outbox que leva só um `workRef` opaco e determinístico. Numa nova tentativa da tarefa, só troca o
  token cifrado do waiter, ou retoma direto se o resultado já tiver chegado.
- O token nunca sai da quote-solution. A provider-solution responde com eventos que levam o
  `workRef`; o `resume` da quote grava o resultado e a intenção de retomada, decifra o token e chama
  `SendTaskSuccess`. Só a quote-solution tem permissão `states:SendTaskSuccess`.
- Recusa comercial (`DECLINED`) também retoma com `SendTaskSuccess`. Timeout da espera é tratado pela
  própria máquina (`TimeoutSeconds` fixo no estado): o `Catch` chama um passo da Lambda
  `workflow-steps`, que registra o desfecho sem sobrescrever resultado já terminal.

## Consequências

- Um resultado tardio encontra o token expirado (`TaskTimedOut`) ou o resultado já terminal; vira
  evidência tardia e não reabre a cotação (RN10).
- A máquina não guarda dados de negócio no estado: os resultados ficam no DynamoDB da quote e o Map
  descarta a saída dos itens (`ResultPath: null`).
- No Floci 2.4.0, `TimeoutSecondsPath` é ignorado e a execução fica parada. Os prazos usam
  `TimeoutSeconds` fixo por estágio, configurado por `params` do `serverless.yml`. O "limitado pelo
  prazo restante" do PRD (§9) é atendido por uma invariante de orçamento, validada por teste de
  configuração: a espera do enriquecimento, mais as rodadas do Map vezes a espera por seguradora, mais
  a margem dos passos, cabe no prazo total da cotação.
- O `TimeoutSeconds` da máquina é o prazo total mais uma margem e serve só de rede de segurança. Ele
  encerra a execução em `TIMED_OUT` sem passar por `Catch`, então a reconciliação finaliza a versão.
- Os passos simples da máquina ficam numa só Lambda Quarkus, `workflow-steps`, que escolhe o passo
  pelo campo `step`, porque no Floci cada função Java vira um container e a memória da máquina de
  desenvolvimento é limitada. `dispatch`, `resume`, `outbox-relay`, `issue-projector` e `reconciler`
  continuam separados. O custo é um papel IAM comum aos passos, restrito à tabela da quote.
- A API de produto em Quarkus atende à meta de latência com folga maior; o custo é a API de callbacks
  e os workers ficarem em Spring, onde o cold start dilui no prazo por seguradora.

## Alternativas consideradas

- Núcleo da cotação em Spring e fornecedores em Quarkus: segue a regra "Spring para workflows
  complexos", mas põe a API com meta de latência no framework de cold start maior.
- Token enviado à provider-solution para ela mesma chamar `SendTaskSuccess`: elimina o resumer, mas
  espalha um segredo operacional por duas soluções e dá à provider permissão sobre a máquina da quote.
