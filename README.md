# mmda-serverless

Ambiente para projetar soluções serverless com AWS Step Functions e AWS Lambda em Java, com Spring
ou Quarkus conforme o propósito de cada solução, implantação pelo Serverless Framework e emulação local da
AWS pelo [Floci](https://github.com/floci-io/floci). As soluções compartilham o ambiente AWS e se
acionam por eventos: a `receipt-solution` (Quarkus) reage ao que a `sample-solution` (Spring) conclui, e a
plataforma de cotação de seguros é formada pela `quote-solution` (Quarkus) e pela `provider-solution`
(Spring), que conversam pelo barramento de `platform/events`, e pelo `backoffice` (Angular), o painel do
corretor e do operador.

## Documentação

O índice de toda a documentação, com o desenho de cada solução, os testes, as decisões (ADRs), os PRDs e
os contratos, está em [docs/README.md](docs/README.md). Cada solução tem um documento principal, com os
diagramas dela: [sample-solution](sample-solution/docs/README.md),
[receipt-solution](receipt-solution/docs/README.md), [quote-solution](quote-solution/docs/README.md),
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
| serverless-step-functions / serverless-localstack / serverless-offline | 3.31.2 / 1.4.0 / 14.8.4 |
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
sample-solution/        Spring: api, functions e e2e
receipt-solution/       Quarkus, acionada pelo RequestCompleted da sample-solution: api, functions e e2e
quote-solution/         Quarkus, API de produto e workflow da cotação: domain, api, functions e e2e
provider-solution/      Spring, fornecedores da cotação: domain, api (callbacks), functions e simulators
backoffice/             Angular, painel da cotação: cotações, ofertas, seleção e ocorrências (pnpm workspace)
contracts/              API de produto (openapi.yaml e fixtures/) e uma pasta de contratos por solução
docs/                   Documentação, ADRs, PRDs e o tema dos diagramas
```

Os módulos de cada solução estão em [Arquitetura](docs/arquitetura.md#módulos-maven) (sample e receipt) e no
[desenho técnico da cotação](docs/plataforma-de-cotacao.md#1-módulos) (quote e provider). Uma solução nova
escolhe o parent do framework em `platform/` e ganha o próprio `serverless.yml`
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
| `deploy:sample` | `sample-solution` |
| `deploy:receipt` | `receipt-solution` |
| `deploy:provider` | `provider-solution` |
| `deploy:quote` | `quote-solution` |

A ordem importa: a regra que aciona a `receipt-solution` fica no barramento da `sample-solution`, e a
`provider-solution` e a `quote-solution` chegam pelo nome ao barramento da plataforma, sem referência
entre stacks ([ADR 0013](docs/decisoes/0013-barramento-da-plataforma.md)). Cada script também roda sozinho,
como `pnpm deploy:quote`, contra os pacotes já gerados.

O stage `local` ativa o `serverless-localstack`, que direciona a implantação para `localhost:4566`.
Ao final, o Serverless mostra o endpoint da API, no formato
`http://localhost:4566/restapis/<api-id>/local/_user_request_`.

Para implantar a plataforma de cotação no stage `dev` de uma conta AWS e verificar o que o Floci não
reproduz, siga o [guia de implantação na AWS](docs/implantacao-aws-dev.md).

## Backoffice

O painel roda no dev server do Angular, contra a `quote-solution` implantada no Floci ou no stage `dev`. O
passo a passo, com a criação dos usuários de exemplo, está em [Rodar no Floci](backoffice/README.md#rodar-no-floci).

## Fluxo de exemplo

`POST /requests` grava o payload no S3 e inicia a máquina de estado `sample-solution-local-request-flow`,
que valida, persiste e notifica; o `RequestCompleted` emitido no fim inicia a `receipt-flow` da
`receipt-solution`. Os passos, os caminhos de falha e o reprocessamento estão em [Fluxos](docs/fluxos.md).

A API da `receipt-solution` consulta o comprovante de um pedido e emite um manualmente:

```bash
RECEIPT_API_ID=$(aws --profile localstack apigateway get-rest-apis \
  --query "items[?name=='local-receipt-solution'].id" --output text)
RECEIPTS="http://localhost:4566/restapis/$RECEIPT_API_ID/local/_user_request_/receipts"

curl "$RECEIPTS/<requestId>"                      # 200 com o comprovante ou 404
curl -X POST -H "Content-Type: application/json" \
  -d '{"requestId":"manual-1","signature":"abc","payloadBytes":3}' "$RECEIPTS"   # 202
```

### Testar o caminho de sucesso

```bash
API_ID=$(aws --profile localstack apigateway get-rest-apis \
  --query "items[?name=='local-sample-solution'].id" --output text)

curl -X POST -H "Content-Type: application/json" -d '{"customer":"acme","amount":42}' \
  "http://localhost:4566/restapis/$API_ID/local/_user_request_/requests"
```

A resposta (`202`) traz o `requestId` e o `executionArn`. Para acompanhar:

```bash
aws --profile localstack stepfunctions describe-execution --execution-arn <executionArn> --query status
aws --profile localstack dynamodb get-item --table-name sample-solution-local-requests \
  --key '{"requestId":{"S":"<requestId>"}}'
```

### Testar o caminho de falha

Um payload acima de 4096 bytes falha no `validate` e vai para a DLQ:

```bash
curl -X POST -H "Content-Type: application/json" \
  -d "$(python3 -c 'import json;print(json.dumps({"data":"x"*5000}))')" \
  "http://localhost:4566/restapis/$API_ID/local/_user_request_/requests"
```

Para reprocessar a DLQ sem esperar o agendamento:

```bash
aws --profile localstack lambda invoke --function-name sample-solution-local-reprocess \
  --cli-binary-format raw-in-base64-out --payload '{"maxMessages":10}' /dev/stdout
```

## Rodar a API com o serverless-offline

O offline executa só as Lambdas e a rota HTTP, em container Docker na rede `floci`. A máquina de
estado e os recursos continuam no Floci, então a implantação acima precisa existir.

```bash
cd sample-solution
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
pnpm sls offline --stage local

curl -X POST -H "Content-Type: application/json" -d '{"origem":"offline"}' \
  http://localhost:3000/local/requests
```

## Remover a implantação do Floci

A `receipt-solution` sai antes da `sample-solution`, porque a regra dela fica no barramento da sample. A
`quote-solution` e a `provider-solution` saem antes da plataforma (`platform/events`), porque as regras
delas ficam no barramento da plataforma; entre as duas, a ordem não importa
([ADR 0013](docs/decisoes/0013-barramento-da-plataforma.md)). Os dois grupos são independentes. O bucket
de payloads da sample e o da provider precisam estar vazios antes da remoção de cada uma:

```bash
(cd receipt-solution && pnpm sls remove --stage local)
aws --profile localstack s3 rm s3://sample-solution-local-payloads --recursive
(cd sample-solution && pnpm sls remove --stage local)

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
  para o host do LocalStack, e o `serverless-offline` repassa essa variável ao container. Por isso o
  `serverless.yml` da `sample-solution` e o da `provider-solution` declaram `AWS_ENDPOINT_URL_S3` apontando
  para o Floci ([ADR 0008](docs/decisoes/0008-aws-endpoint-url-s3-no-offline.md)).
- Na `sample-solution` e na `receipt-solution`, os parâmetros de ambiente (`params`) do `serverless.yml`
  existem só para o stage `local`. Na `quote-solution` e na `provider-solution`, o `params.default` traz os
  valores da AWS, o `local` sobrescreve só o que difere no Floci, e as variáveis de endpoint
  (`AWS_ENDPOINT_URL` e, na provider, `AWS_ENDPOINT_URL_S3`) só existem quando o param `awsEndpointUrl` não
  é vazio, pela condição `HasAwsEndpointUrl`
  ([parâmetros por stage](docs/plataforma-de-cotacao.md#12-parâmetros-por-stage)).
- O `pnpm-workspace.yaml` nega os scripts de instalação de `serverless`, `aws-sdk`, `es5-ext` e
  `esbuild`; nenhum deles é necessário para o uso atual.
- Os pacotes das Lambdas têm entre 21 e 41 MiB. O `aws-crt` entra só com os nativos de Linux, para
  x86_64 e arm64; os testes usam o jar completo, com os nativos de todas as plataformas
  ([ADR 0012](docs/decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md)).
- O EventBridge Scheduler dispara o `reprocess` no Floci a cada 5 minutos (observado nos logs da
  função às 20:08:56 e 20:14:02 de 10/11/2026); para testar sem esperar, invoque a função direto.
- O servidor Java do VS Code (m2e) compila em `target-ide/`, pelo perfil `ide` do `pom.xml` raiz, que a
  propriedade `m2e.version` ativa. Assim ele não reescreve o `target/classes` no meio de um `mvn verify`; o
  `mvn` da linha de comando continua em `target/`.
