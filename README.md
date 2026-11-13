# mmda-serverless

Ambiente para projetar soluções serverless com AWS Step Functions e AWS Lambda em Java, com Spring
ou Quarkus conforme o propósito de cada solução, implantação pelo Serverless Framework e emulação local da
AWS pelo [Floci](https://github.com/floci-io/floci). A solução de demonstração é a plataforma de cotação
de seguros: a `quote-solution` (Quarkus) e a `provider-solution` (Spring) compartilham o ambiente AWS e
conversam pelo barramento de `platform/events`, e o `backoffice` (Angular) é o painel do corretor e do
operador.

## Documentação

O índice de toda a documentação, com o desenho de cada solução, os testes, as decisões (ADRs), os PRDs e
os contratos, está em [docs/README.md](docs/README.md). Cada solução tem um documento principal, com os
diagramas dela: [quote-solution](quote-solution/docs/README.md),
[provider-solution](provider-solution/docs/README.md), [backoffice](backoffice/README.md) e
[plataforma](platform/docs/README.md); a entrada da plataforma de cotação é [docs/cotacao.md](docs/cotacao.md).

## Tecnologias

| Peça | Versão |
|---|---|
| Java (runtime `java21` da Lambda) | 21 |
| Maven | 3.9 |
| Spring Boot | 4.0.9 |
| Spring Cloud / Spring Cloud Function | 2025.1.4 / 5.0.5 |
| aws-serverless-java-container-springboot4 | 3.0.5 |
| Quarkus, linha LTS (plataforma e BOMs) / extensões do Quarkus Amazon Services | 3.40.1.2 / 3.22.1 |
| Jackson (Spring / Quarkus) | 3.1.7 / 2.21.8 |
| AWS SDK for Java v2, clients síncronos com CRT (Spring / Quarkus) | 2.57.12 / 2.54.13 |
| Testcontainers / testcontainers-floci-aws | 2.0.5 / 2.17.0 |
| Serverless Framework | 4.46.0 |
| serverless-step-functions / serverless-localstack | 3.31.2 / 1.4.0 |
| Floci / Floci UI | 2.4.0 / 0.7.0 |
| Backoffice: Node.js / Angular / CoreUI for Angular ([detalhes](backoffice/README.md#stack)) | 24 / 22.2 / 5.7.42 |

## Estrutura

```text
compose.yaml            Floci na porta 4566, com acesso ao Docker para executar as Lambdas, e a
                        console floci-ui na porta 4500
package.json            Serverless Framework e plugins (pnpm) e os scripts de implantação local
pom.xml                 POM raiz neutro: Java 21, versões de plugins e testcontainers-floci-aws
platform/
  events/               serverless.yml do barramento platform-<stage>-events da plataforma de cotação
  spring-parent/        Parent das soluções Spring: Spring Boot, Spring Cloud, AWS SDK, exclusões de clients HTTP e zip das Lambdas
  quarkus-parent/       Parent das soluções Quarkus: Quarkus LTS, Quarkus Amazon Services e zip por módulo
  spring-aws/           Spring: autoconfiguração dos clients do AWS SDK v2 (cada client sobe se o SDK estiver no módulo)
quote-solution/         Quarkus, API de produto e workflow da cotação: domain, api, functions e e2e
provider-solution/      Spring, fornecedores da cotação: domain, api (callbacks), functions e simulators
backoffice/             Angular, painel da cotação: cotações, ofertas, seleção e ocorrências (pnpm workspace)
contracts/              API de produto (openapi.yaml e fixtures/) e uma pasta de contratos por solução
docs/                   Documentação, ADRs, PRDs e o tema dos diagramas
```

Os módulos da raiz e da `platform/` estão em [Arquitetura](docs/arquitetura.md#módulos-maven), e os da
`quote-solution` e da `provider-solution`, no
[desenho técnico da cotação](docs/plataforma-de-cotacao.md#1-módulos) e no documento principal de cada uma
([quote-solution](quote-solution/docs/README.md#módulos-maven) e
[provider-solution](provider-solution/docs/README.md)). Uma solução nova escolhe o parent do framework em
`platform/` e ganha o próprio `serverless.yml`
([ADR 0010](docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md)).

## Pré-requisitos

- JDK 21 e Maven 3.9
- Docker com o daemon rodando
- Node.js 24 e pnpm
- AWS CLI v2
- Login no Serverless Framework v4: `pnpm sls login`

## Primeira execução

```bash
docker compose up -d
pnpm install
mvn -B package
```

O `docker compose up -d` também sobe a console web do Floci em http://localhost:4500, para ver as
tabelas, filas, funções, execuções e logs implantados.

O `mvn package` gera, no `target/` de cada módulo de Lambda, o zip que o Serverless envia; o formato e os
nomes estão em [Empacotamento](docs/arquitetura.md#empacotamento).

## Testes

Níveis, comandos (`mvn test`, `mvn verify` e `pnpm test:e2e`) e o que cada teste cobre estão em
[Testes](docs/testes.md). Os do backoffice (`pnpm --filter backoffice test` e `pnpm --filter backoffice e2e`)
estão no [README dele](backoffice/README.md#testes).

## Implantação no Floci

```bash
pnpm deploy:local
```

O `deploy:local` empacota os módulos sem rodar testes (`mvn -B -q package -DskipTests`) e implanta as
stacks no stage `local`, nesta ordem:

| Script | Stack |
|---|---|
| `deploy:platform` | Barramento da plataforma (`platform/events`) |
| `deploy:provider` | `provider-solution` |
| `deploy:quote` | `quote-solution` |

A ordem importa: a plataforma vem primeiro, porque a `provider-solution` e a `quote-solution` chegam pelo
nome ao barramento dela, sem referência entre stacks
([ADR 0013](docs/decisoes/0013-barramento-da-plataforma.md)). Cada script também roda sozinho, como
`pnpm deploy:quote`, contra os pacotes já gerados.

O stage `local` ativa o `serverless-localstack`, que direciona a implantação para `localhost:4566`.
Ao final, o Serverless mostra o endpoint da API, no formato
`http://localhost:4566/restapis/<api-id>/local/_user_request_`.

Para implantar a plataforma de cotação no stage `dev` de uma conta AWS e verificar o que o Floci não
reproduz, siga o [guia de implantação na AWS](docs/implantacao-aws-dev.md).

## Backoffice

O painel roda no dev server do Angular, contra a `quote-solution` implantada no Floci ou no stage `dev`. O
passo a passo, com a criação dos usuários de exemplo, está em [Rodar no Floci](backoffice/README.md#rodar-no-floci).

## Exercitar a cotação

Com as stacks implantadas, o passo a passo para chamar a API da `quote-solution` (usuário no Cognito do
Floci, token e abertura de uma cotação) está em
[Rodar localmente no Floci](quote-solution/docs/README.md#rodar-localmente-no-floci). Pela interface, use o
[backoffice](#backoffice).

## Remover a implantação do Floci

A `quote-solution` e a `provider-solution` saem antes da plataforma (`platform/events`), porque as regras
delas ficam no barramento da plataforma; entre as duas, a ordem não importa
([ADR 0013](docs/decisoes/0013-barramento-da-plataforma.md)). O bucket de payloads da provider precisa
estar vazio antes da remoção dela:

```bash
(cd quote-solution && pnpm sls remove --stage local)
aws --profile localstack s3 rm s3://provider-solution-local-payloads --recursive
(cd provider-solution && pnpm sls remove --stage local)
(cd platform/events && pnpm sls remove --stage local)
```

## Perfil do AWS CLI

Os comandos de exemplo usam o perfil `localstack`, que aponta para o Floci com as chaves fixas de
teste. Para criá-lo:

```bash
aws configure set region us-east-1 --profile localstack
aws configure set endpoint_url http://localhost:4566 --profile localstack
aws configure set aws_access_key_id test --profile localstack
aws configure set aws_secret_access_key test --profile localstack
```

## Pontos de atenção

- O Floci roda em modo persistente, com os dados no volume `floci-data`: os recursos sobrevivem a
  reinícios do container. Para começar do zero, use `docker compose down -v` e refaça a implantação.
- Quando o `serverless-localstack` está ativo, o Serverless v4 exporta `AWS_ENDPOINT_URL_S3` apontando
  para o host do LocalStack, e a variável específica do serviço tem prioridade sobre `AWS_ENDPOINT_URL` no
  AWS SDK. Por isso o `serverless.yml` da `provider-solution` declara `AWS_ENDPOINT_URL_S3` apontando para o
  Floci ([ADR 0008](docs/decisoes/0008-aws-endpoint-url-s3-no-offline.md)).
- Na `quote-solution` e na `provider-solution`, os parâmetros de ambiente (`params`) do `serverless.yml`
  têm o `params.default` com os valores da AWS, e o `local` sobrescreve só o que difere no Floci. As
  variáveis de endpoint (`AWS_ENDPOINT_URL` e, na provider, `AWS_ENDPOINT_URL_S3`) só existem quando o param
  `awsEndpointUrl` não é vazio, pela condição `HasAwsEndpointUrl`
  ([parâmetros por stage](docs/plataforma-de-cotacao.md#12-parâmetros-por-stage)).
- O `pnpm-workspace.yaml` nega os scripts de instalação de `serverless`, `aws-sdk`, `es5-ext` e
  `esbuild`; nenhum deles é necessário para o uso atual.
- Os pacotes das Lambdas têm entre 24 e 38 MiB. O `aws-crt` entra só com os nativos de Linux, para
  x86_64 e arm64; os testes usam o jar completo, com os nativos de todas as plataformas
  ([ADR 0012](docs/decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md)).
- O EventBridge Scheduler dispara o `reconciler` da `quote-solution` e o da `provider-solution` a cada
  minuto (`rate(1 minute)`), em intervalos de 60 a 70 s no Floci
  ([limites do Floci](docs/plataforma-de-cotacao.md#13-limites-do-floci-240)); para testar sem esperar,
  invoque a função direto.
- O servidor Java do VS Code (m2e) compila em `target-ide/`, pelo perfil `ide` do `pom.xml` raiz, que a
  propriedade `m2e.version` ativa. Assim ele não reescreve o `target/classes` no meio de um `mvn verify`; o
  `mvn` da linha de comando continua em `target/`.
