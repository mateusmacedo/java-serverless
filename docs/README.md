# Documentação

Índice da documentação do ambiente e das soluções: a `sample-solution`, em Spring, e a `receipt-solution`,
em Quarkus, acionada pelos eventos da primeira; e a plataforma de cotação de seguros, formada pela
`quote-solution`, em Quarkus, e pela `provider-solution`, em Spring, com o barramento de
`platform/events`. Para subir o ambiente e executar os comandos do dia a dia, comece pelo
[README da raiz](../README.md).

## Sumário

- [Soluções](#soluções)
- [Documentos](#documentos)
- [Contratos](#contratos)
- [Decisões](#decisões)
- [Convenções](#convenções)
- [Diagramas](#diagramas)
- [Como validar](#como-validar)

## Soluções

Cada solução tem um documento principal em `<solução>/docs/README.md`, com funções, API, eventos,
persistência, IAM, configuração, execução no Floci, divergências conhecidas e os diagramas PlantUML dela.

| Documento | Para que serve |
|---|---|
| [Cotação](cotacao.md) | Entrada da plataforma de cotação: módulos, capacidades, contrato com os clientes, C4 de contexto e ADRs |
| [quote-solution](../quote-solution/docs/README.md) | API de produto, workflow da versão, consolidação, seleção, reconciliação e operações da cotação |
| [provider-solution](../provider-solution/docs/README.md) | Enriquecimento, chamadas às seguradoras, callbacks, outbox, reconciliação, DLQs e simuladores |
| [sample-solution](../sample-solution/docs/README.md) | Solução de referência em Spring: requisição, `request-flow`, DLQ e reprocessamento |
| [receipt-solution](../receipt-solution/docs/README.md) | Emissão idempotente de comprovantes em Quarkus, acionada pelo `RequestCompleted` |
| [Plataforma](../platform/docs/README.md) | Barramento `platform/events`, parents Maven, `spring-aws` e ordem de implantação |

## Documentos

| Documento | Para que serve |
|---|---|
| [Guia do código](guia-do-codigo.md) | Como cada Lambda da `sample-solution` e da `receipt-solution` sobe, como os componentes são registrados e por onde a requisição passa |
| [Arquitetura](arquitetura.md) | Contexto, contêineres, módulos, Lambdas, recursos AWS, configuração e ambiente local da `sample-solution` e da `receipt-solution` |
| [Fluxos](fluxos.md) | Máquina de estado e sequência dos caminhos de sucesso, falha e reprocessamento da `sample-solution` e da `receipt-solution` |
| [Plataforma de cotação: desenho técnico](plataforma-de-cotacao.md) | Módulos, recursos, eventos, tabelas, máquina de estado, funções, fluxos, falhas, simuladores, autenticação, parâmetros por stage, limites do Floci e testes por marco da `quote-solution` e da `provider-solution` |
| [Implantação e verificação no stage `dev` da AWS](implantacao-aws-dev.md) | Implantação da plataforma de cotação numa conta AWS e roteiro de verificação do que o Floci não reproduz |
| [Testes](testes.md) | Níveis de teste, o que cada classe cobre, como rodar e os números das rodadas de fechamento |
| [PRD de backend](prds/prd-cotacao-seguros-backend-v2.md) | Requisitos da plataforma de cotação no backend |
| [PRD de frontend](prds/prd-cotacao-seguros-frontend-v2.md) | Requisitos do frontend da plataforma de cotação |

## Contratos

Cada solução tem a pasta `contracts/<solução>/`, com um `README.md` que diz o que cada contrato descreve,
quem produz, quem consome e como os testes o conferem. A API de produto da cotação fica na raiz de
`contracts/`, no caminho fixado pelos dois PRDs.

| Contratos | Arquivos |
|---|---|
| [`sample-solution`](../contracts/sample-solution/README.md) | [OpenAPI 3.1](../contracts/sample-solution/openapi.yaml) do `POST /requests` e [AsyncAPI 3.1](../contracts/sample-solution/asyncapi.yaml) do estado entre os passos, do SNS, do `RequestCompleted`, das filas `audit` e `dlq` e da invocação do reprocessamento |
| [`receipt-solution`](../contracts/receipt-solution/README.md) | [OpenAPI 3.1](../contracts/receipt-solution/openapi.yaml) do `GET /receipts/{requestId}` e do `POST /receipts` e [AsyncAPI 3.1](../contracts/receipt-solution/asyncapi.yaml) do `RequestCompleted` consumido e do `ReceiptIssued` publicado |
| [`provider-solution`](../contracts/provider-solution/README.md) | [OpenAPI 3.1](../contracts/provider-solution/openapi.yaml) do `POST /v1/providers/{provider}/callbacks` e [AsyncAPI 3.1](../contracts/provider-solution/asyncapi.yaml) dos eventos publicados no barramento da plataforma |
| [`quote-solution`](../contracts/quote-solution/README.md) | [AsyncAPI 3.1](../contracts/quote-solution/asyncapi.yaml) dos eventos publicados e consumidos no barramento da plataforma |
| API de produto | [`contracts/openapi.yaml`](../contracts/openapi.yaml) (OpenAPI 3.1, compartilhado com o frontend) e [`contracts/fixtures/`](../contracts/fixtures) |

## Decisões

| ADR | Decisão |
|---|---|
| [0001](decisoes/0001-serverless-framework-e-floci.md) | Serverless Framework v4 com Floci como emulador local |
| [0002](decisoes/0002-spring-mvc-e-spring-cloud-function.md) | Spring MVC na API e Spring Cloud Function nos passos |
| [0003](decisoes/0003-spring-boot-3-5.md) | Spring Boot 3.5 pela adoção de mercado (substituída pelo 0009) |
| [0004](decisoes/0004-sem-graalvm-native.md) | Sem imagem nativa GraalVM |
| [0005](decisoes/0005-clients-sincronos-com-crt.md) | Clients síncronos do AWS SDK com CRT e SDKs opcionais (o item do jar do `aws-crt` foi substituído pelo 0012) |
| [0006](decisoes/0006-payload-no-s3.md) | Payload no S3, com só a chave na máquina de estado |
| [0007](decisoes/0007-dlq-e-reprocessamento.md) | Falhas para a DLQ e reprocessamento com limite de tentativas |
| [0008](decisoes/0008-aws-endpoint-url-s3-no-offline.md) | `AWS_ENDPOINT_URL_S3` explícito para o serverless-offline |
| [0009](decisoes/0009-spring-boot-4-0.md) | Spring Boot 4.0 com Jackson 3 |
| [0010](decisoes/0010-raiz-neutra-e-parents-por-framework.md) | Raiz neutra e um parent por framework (Spring e Quarkus) |
| [0011](decisoes/0011-integracao-entre-solucoes-por-eventbridge.md) | Soluções se acionam pelo EventBridge, com a máquina de estado como alvo nativo |
| [0012](decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md) | Pacote da Lambda com os jars Linux do `aws-crt` (substitui um item do 0005) |
| [0013](decisoes/0013-barramento-da-plataforma.md) | Barramento de eventos da plataforma, em stack própria |
| [0014](decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md) | Cotação em duas soluções, com o task token restrito à `quote-solution` |
| [0015](decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md) | Uma tabela por solução, outbox publicada por DynamoDB Streams |

Um novo ADR recebe o próximo número, com as seções Status, Data, Contexto, Decisão, Consequências e
Alternativas consideradas. Uma decisão substituída continua no histórico, com o status apontando
para o ADR que a substitui.

## Convenções

- Documentação em PT-BR, versionada junto do código; nomes de classes, propriedades e recursos ficam
  como estão no código. Nomes de arquivo em kebab-case, sem acento.
- Cada assunto tem uma fonte única; os outros documentos apontam para ela por link, em vez de copiar.
- Todos os diagramas são PlantUML, com o tema único da [seção Diagramas](#diagramas): os de cada
  solução ficam em `<solução>/docs/diagramas/`, e os de `docs/*.md`, como os da
  [Arquitetura](arquitetura.md), dos [Fluxos](fluxos.md) e da [Cotação](cotacao.md), em blocos
  `plantuml` dentro do próprio Markdown.
- Fatos sobre o sistema vêm do código e do `serverless.yml` de cada solução. Exemplos de mensagens
  foram capturados no Floci; quando não foi possível observar, o documento diz isso.
- Cada solução documenta, em `contracts/<solução>/`, os eventos que publica. Quem consome referencia o
  contrato da produtora, sem copiá-lo.
- Ao mudar o fluxo, a API ou os eventos, atualize no mesmo commit o diagrama e o contrato afetados.

## Diagramas

O tema PlantUML único é o [`diagramas/tema-plataforma.iuml`](diagramas/tema-plataforma.iuml): paleta por
camada (`$COR_*` e `$BORDA_*`), ícones prontos (`$I_*`), fonte e `skinparam` base, estereótipos coloridos
para participantes, componentes, classes, casos de uso e estados, e as legendas `$legenda_camadas()` e
`$legenda_erros()`. Todo `.puml` começa com `@startuml <nome-do-arquivo>` e inclui o tema na linha 2, com
caminho relativo: `!include tema-plataforma.iuml` em `docs/diagramas/`,
`!include ../../../docs/diagramas/tema-plataforma.iuml` em `<solução>/docs/diagramas/` e
`!include diagramas/tema-plataforma.iuml` num bloco `plantuml` de `docs/*.md`. Os sprites vêm da
biblioteca padrão embutida no PlantUML, sem rede.

Os renderizados vão para `build/diagramas/<pasta>/`, que o `.gitignore` ignora (`**/build/`); nenhum SVG ou
PNG é versionado. Cada solução e cada documento de `docs/` gravam na própria pasta, porque há diagramas
de mesmo nome em pastas diferentes. Num `.md`, o PlantUML renderiza cada bloco `@startuml` com o nome
dado nele. Para renderizar tudo, na raiz do projeto:

```bash
for dir in platform sample-solution receipt-solution quote-solution provider-solution; do
  docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
    -tsvg -o "/w/build/diagramas/$dir" "$dir/docs/diagramas/*.puml"
done
for doc in arquitetura fluxos cotacao; do
  docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
    -tsvg -o "/w/build/diagramas/$doc" "docs/$doc.md"
done
```

## Como validar

Execute na raiz do projeto. As ferramentas são baixadas sob demanda pelo `pnpm dlx`, sem entrar no
`package.json`.

```bash
# OpenAPI
for spec in contracts/openapi.yaml contracts/*/openapi.yaml; do
  pnpm --package=@redocly/cli@2.77.0 dlx redocly lint "$spec"
done

# AsyncAPI: ignore-scripts evita o script de instalação da CLI, que altera ~/.zshrc e ~/.bashrc
for spec in contracts/*/asyncapi.yaml; do
  pnpm --config.ignore-scripts=true --package=@asyncapi/cli@6.3.0 dlx asyncapi validate "$spec"
done
```

Os diagramas são validados pelo comando da [seção Diagramas](#diagramas): quando um diagrama tem erro
de sintaxe, o `docker run` correspondente sai com o código 200 e indica o arquivo e a linha do erro.

Resultado registrado na última validação com as CLIs, feita nos contratos da `sample-solution` e da
`receipt-solution`; os demais contratos são conferidos pelos testes de contrato, descritos no `README.md`
de cada pasta de `contracts/`:

- **Redocly:** os dois documentos válidos, com avisos conhecidos (3 no da sample e 2 no da receipt):
  não há licença declarada no projeto (`info-license`) e os servidores apontam para `localhost`
  (`no-server-example.com`), que é o ambiente local de propósito.
- **AsyncAPI:** os dois documentos válidos, sem apontamentos de governança; o da `receipt-solution`
  resolve a referência ao `RequestCompleted` no da `sample-solution`. A CLI envia métricas anônimas de uso
  enquanto `~/.asyncapi-analytics` estiver com `analyticsEnabled` ligado.
- **PlantUML:** 103 SVGs, um por diagrama, sem erro: 93 dos `.puml` de `docs/diagramas/` da `platform` e
  das quatro soluções e 10 dos blocos de `docs/*.md` (3 em `arquitetura.md`, 6 em `fluxos.md` e 1 em
  `cotacao.md`).
