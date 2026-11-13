# Cotação de seguros

Contexto da plataforma de cotação de seguros de automóvel: a `quote-solution` (Quarkus) recebe o pedido,
orquestra a cotação de cada versão e expõe a API de produto; a `provider-solution` (Spring) fala com os
fornecedores (enriquecimento e seguradoras), com reuso, quota, callbacks e reconciliação de consultas; as
duas conversam pelo barramento `platform-<stage>-events`; o `backoffice` (Angular) é o painel do corretor e do
operador sobre a API de produto. Os requisitos estão nos PRDs de
[backend](prds/prd-cotacao-seguros-backend-v2.md) e de [frontend](prds/prd-cotacao-seguros-frontend-v2.md);
o detalhe de implementação, no [desenho técnico](plataforma-de-cotacao.md).

Este arquivo é o ponto de entrada do contexto. O detalhe de cada módulo fica na pasta `docs/` dele.

## Sumário

- [Módulos](#módulos)
- [Capacidades](#capacidades)
- [Contrato com os clientes](#contrato-com-os-clientes)
- [Diagrama de contexto](#diagrama-de-contexto)
- [Decisões relacionadas](#decisões-relacionadas)
- [Diagramas](#diagramas)
- [Desenho técnico](#desenho-técnico)

## Módulos

| Módulo | Tecnologia | Papel | Documentação |
|---|---|---|---|
| `platform/events` | Serverless, sem funções | Barramento `platform-<stage>-events`; sobe antes das duas soluções | [platform/docs](../platform/docs/README.md#barramento-de-eventos) e [ADR 0013](decisoes/0013-barramento-da-plataforma.md) |
| `quote-solution` | Quarkus, Step Functions, DynamoDB | API de produto, máquina de estado `quote-flow`, regras de elegibilidade e de seleção de seguradoras, consolidação, seleção de oferta, outbox, waiters, operações e reconciliação das cotações | [quote-solution/docs](../quote-solution/docs/README.md) |
| `provider-solution` | Spring, SQS, DynamoDB, S3 | Workers de enriquecimento e de seguradoras, reuso com liderança, quota por provedor, API de callbacks com inbox, reconciliação de consultas e simuladores de fornecedores | [provider-solution/docs](../provider-solution/docs/README.md) |
| `backoffice` | Angular, CoreUI | Painel do PRD de frontend: lista, nova cotação, acompanhamento com polling, comparação, complementação, seleção e ocorrências, com login no user pool da `quote-solution` | [backoffice/README](../backoffice/README.md) e [ADR 0016](decisoes/0016-backoffice-angular-e-login-cognito.md) |

Os módulos Maven de cada solução e os pacotes Java estão no [§1 do desenho](plataforma-de-cotacao.md#1-módulos);
os recursos de cada stack, no [§2](plataforma-de-cotacao.md#2-recursos-por-stack).

## Capacidades

| Capacidade | Onde fica |
|---|---|
| Criar cotação e nova versão, com idempotência por `Idempotency-Key` | `quote-solution`: `POST /v1/quotes` e `POST /v1/quotes/{quoteId}/versions` |
| Acompanhar a versão (fase, outcome, resultados por seguradora, ofertas e ações permitidas) | `quote-solution`: `GET /v1/quotes`, `GET .../versions/{version}` e `GET .../offers` |
| Elegibilidade (`elig-1`) e seleção de seguradoras (`sel-1`) | `quote-solution`, `quote-domain` |
| Enriquecimento obrigatório do veículo e do condutor, com reuso (`HIT`, `JOIN`, `MISS`) | Pedido e espera na `quote-solution`; consulta, reuso e quota na `provider-solution` |
| Cotação em seguradora síncrona (alpha) e assíncrona (beta, com `202` e callback assinado) | Map da `quote-flow` na `quote-solution`; chamada, callbacks e inbox na `provider-solution` |
| Consolidação do outcome pela precedência do PRD | `quote-solution`, `Consolidation` |
| Seleção de oferta (intenção de proposta, uma por versão) | `quote-solution`: `POST .../selection` |
| Catálogo do laboratório (clientes, veículos, condutores e perfis de cobertura) | `contracts/fixtures/catalog.json`, servido pela `quote-solution` em `/v1/catalog/*` e lido pelos simuladores da `provider-solution` |
| Ocorrências operacionais e reconciliação manual de consultas | Projeção e API na `quote-solution` (`/v1/operations/*`); reconciliação na `provider-solution` |
| Reconciliação automática, DLQs com limite de tentativas, alarmes e métricas EMF | `reconciler` de cada solução ([ADR 0007](decisoes/0007-dlq-e-reprocessamento.md)) |
| Autenticação por access token do Cognito, com organização e permissões por grupo | `quote-solution` (user pool da stack) |

## Contrato com os clientes

- **API de produto:** [`contracts/openapi.yaml`](../contracts/openapi.yaml), OpenAPI 3.1 na versão `1.0.0`,
  compartilhado com o [backoffice](../backoffice/README.md) no caminho fixado pelos dois PRDs. Cobre as 14 operações da seção "Contrato
  compartilhado de integração — versão 2.0", com a permissão de cada uma em `x-permissions`, paginação por
  cursor, `Idempotency-Key` nas mutações e o erro padrão.
- **Fixtures:** [`contracts/fixtures/`](../contracts/fixtures) traz o `catalog.json` do laboratório (o mesmo
  arquivo vai para o classpath do `quote-api` e do `provider-simulators`), o `catalog-pages.json` com as
  páginas que a API responde e as trocas de exemplo (progresso, oferta parcial, recusa, pendência,
  expiração, conflitos, erros, seleção, listagem, operações e `/v1/me`).
- **Eventos entre as soluções:** cada solução documenta o que publica e o que consome em
  `contracts/<solução>/`: [quote-solution](../contracts/quote-solution/README.md) e
  [provider-solution](../contracts/provider-solution/README.md). O callback das seguradoras assíncronas está
  no OpenAPI da provider.
- **Como os testes conferem:** os testes de contrato da `quote-api` e o `ContractClient` do `quote-e2e`
  validam cada troca contra o `contracts/openapi.yaml`; os detalhes estão em
  [Testes](testes.md#contrato). Os testes do backoffice respondem com as mesmas fixtures
  ([Testes](testes.md#backoffice)). Os comandos de validação com as CLIs estão em
  [Como validar](README.md#como-validar).

## Diagrama de contexto

```plantuml
@startuml c4-contexto
!include diagramas/tema-plataforma.iuml
!$LEGEND_TITLE_TEXT = "Legenda"
!include <C4/C4_Context>

UpdateElementStyle("person", $bgColor="#FFFDE7", $fontColor="#37474F", $borderColor="#F9A825", $legendText="Pessoa")
UpdateElementStyle("system", $legendText="Sistema")
UpdateElementStyle("external_system", $bgColor="#FCE4EC", $fontColor="#37474F", $borderColor="#AD1457", $legendText="Sistema externo")
UpdateBoundaryStyle($elementName="enterprise", $bgColor="#FAFCFF", $fontColor="#1A237E", $borderColor="#1565C0", $type="contexto", $legendText="Plataforma de cotação")
AddElementTag("quarkus", $bgColor="#1565C0", $fontColor="#FFFFFF", $borderColor="#0D47A1", $legendText="Solução em Quarkus")
AddElementTag("spring", $bgColor="#2E7D32", $fontColor="#FFFFFF", $borderColor="#1B5E20", $legendText="Solução em Spring")
AddElementTag("angular", $bgColor="#C2185B", $fontColor="#FFFFFF", $borderColor="#880E4F", $legendText="Frontend em Angular")
AddElementTag("barramento", $bgColor="#F3E5F5", $fontColor="#4A148C", $borderColor="#6A1B9A", $legendText="Barramento da plataforma")
AddElementTag("simulado", $bgColor="#FCE4EC", $fontColor="#37474F", $borderColor="#AD1457", $borderStyle=DashedLine(), $legendText="Fornecedor simulado no laboratório")
AddRelTag("assincrono", $textColor="#6A1B9A", $lineColor="#6A1B9A", $lineStyle=DashedLine(), $legendText="Assíncrono (EventBridge e SQS)")

title $I_VEICULO Cotação de seguros — contexto (C4 nível 1)

Person(usuario, "Usuário da organização", "Cria cotações, acompanha as versões e seleciona ofertas", $sprite="user")
Person(operador, "Operador da organização", "Acompanha ocorrências e pede reconciliação de consultas", $sprite="user_cog")
Enterprise_Boundary(plataforma, "Plataforma de cotação") {
    System(frontend, "backoffice", "Painel Angular: cotações, ofertas, seleção e ocorrências; cliente gerado do contracts/openapi.yaml", $sprite="globe", $tags="angular")
    System(quote, "quote-solution", "API de produto, quote-flow, consolidação, seleção, operações e reconciliação das cotações", $sprite="cubes", $tags="quarkus")
    System(bus, "platform-<stage>-events", "Barramento EventBridge da stack platform", $sprite="exchange_alt", $tags="barramento")
    System(provider, "provider-solution", "Enriquecimento com reuso, seguradoras, quota, callbacks e reconciliação de consultas", $sprite="leaf", $tags="spring")
}

System_Ext(enriquecimento, "Provedor de enriquecimento", "enrichment-sim: perfil do veículo e do condutor", $sprite="robot", $tags="simulado")
System_Ext(alpha, "Seguradora alpha", "Cotação síncrona", $sprite="building", $tags="simulado")
System_Ext(beta, "Seguradora beta", "Cotação assíncrona: 202, callback e API de situação", $sprite="building", $tags="simulado")

Rel(usuario, frontend, "Usa")
Rel(operador, frontend, "Usa")
Rel(frontend, quote, "API de produto /v1, access token do Cognito", "HTTPS JSON")
Rel(quote, bus, "EnrichmentRequested, InsurerQuoteRequested, QueryReconcileRequested, QuoteAccepted, QuoteCompleted, OfferSelected", "source mmda.quote", $tags="assincrono")
Rel(bus, provider, "Pedidos, pelas regras da provider", "SQS", $tags="assincrono")
Rel(provider, bus, "EnrichmentResolved, InsurerResultRecorded, QueryReconciled, OperationIssueRaised, OperationIssueUpdated", "source mmda.provider", $tags="assincrono")
Rel(bus, quote, "Resultados e ocorrências, pelas regras da quote", "SQS", $tags="assincrono")
Rel(provider, enriquecimento, "POST /sim/enrichment/profiles", "HTTP")
Rel(provider, alpha, "POST /sim/insurers/alpha/quotes", "HTTP")
Rel(provider, beta, "POST /sim/insurers/beta/quotes e GET .../{ref}", "HTTP")
Rel(beta, provider, "Callback assinado (X-Signature)", "POST /v1/providers/{provider}/callbacks")

SHOW_LEGEND()
@enduml
```

Os fornecedores são simulados no laboratório pelo módulo `provider-simulators`, na própria stack da
provider; a regra de cada cenário está no [§10 do desenho](plataforma-de-cotacao.md#10-simuladores-e-cenários).

## Decisões relacionadas

| ADR | Assunto |
|---|---|
| [0001](decisoes/0001-serverless-framework-e-floci.md) | Serverless Framework v4 para implantar e Floci como emulador local |
| [0004](decisoes/0004-sem-graalvm-native.md) | Lambdas na JVM (`java21`), sem imagem nativa |
| [0005](decisoes/0005-clients-sincronos-com-crt.md) | Clients síncronos do AWS SDK com o client HTTP CRT, nas duas soluções |
| [0007](decisoes/0007-dlq-e-reprocessamento.md) | DLQ com reprocessamento pelo `reconciler` e limite de tentativas no atributo `dlqAttempt` |
| [0008](decisoes/0008-aws-endpoint-url-s3-no-offline.md) | `AWS_ENDPOINT_URL_S3` explícito, com a nota de evolução do M6 na provider |
| [0010](decisoes/0010-raiz-neutra-e-parents-por-framework.md) | `quarkus-parent` para a quote e `spring-parent` para a provider |
| [0011](decisoes/0011-integracao-entre-solucoes-por-eventbridge.md) | Integração entre soluções por eventos; base que o 0013 adapta à plataforma |
| [0012](decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md) | Pacote da Lambda com os jars Linux do `aws-crt` |
| [0013](decisoes/0013-barramento-da-plataforma.md) | Barramento da plataforma em stack própria, regras com filas SQS como alvo |
| [0014](decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md) | Cotação em duas soluções, com o task token restrito à `quote-solution` |
| [0015](decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md) | Uma tabela por solução e outbox publicada por DynamoDB Streams |
| [0016](decisoes/0016-backoffice-angular-e-login-cognito.md) | Backoffice em Angular com o template do marketplace e login no user pool da stack |

## Diagramas

O tema único é o [`diagramas/tema-plataforma.iuml`](diagramas/tema-plataforma.iuml). Os diagramas de cada
solução ficam na pasta `docs/diagramas/` dela, e o de contexto é o bloco acima. Para renderizar, na raiz do
projeto, cada um na própria pasta de saída, porque as soluções têm diagramas com o mesmo nome:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/cotacao docs/cotacao.md
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/quote-solution 'quote-solution/docs/diagramas/*.puml'
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/provider-solution 'provider-solution/docs/diagramas/*.puml'
```

Os SVG vão para `build/diagramas/`, que o `**/build/` do `.gitignore` ignora. Ao mover um `.puml` de pasta,
ajuste o `!include` do tema.

## Desenho técnico

O [desenho técnico da plataforma de cotação](plataforma-de-cotacao.md) é a fonte única de módulos,
recursos, eventos, tabelas, máquina de estado, funções, fluxos, falhas e orçamento de tempo, simuladores,
autenticação, parâmetros por stage, limites do Floci e testes por marco. A implantação no stage `dev` está
em [Implantação e verificação na AWS](implantacao-aws-dev.md), e os testes, em [Testes](testes.md).
