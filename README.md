# mmda-serverless

Ambiente para projetar soluções serverless com AWS Step Functions e AWS Lambda em Java, com Spring
ou Quarkus conforme o propósito de cada solução, deploy pelo Serverless Framework e emulação local da
AWS pelo [Floci](https://github.com/floci-io/floci). As soluções compartilham o ambiente AWS e se
acionam por eventos: a `receipt-solution` (Quarkus) reage ao que a `sample-solution` (Spring) conclui.

## Documentação

| Documento | Conteúdo |
|---|---|
| [Índice da documentação](docs/README.md) | Mapa dos documentos, convenções e como validar |
| [Guia do código](docs/code-walkthrough.md) | Por onde começar a ler: como o código sobe e por onde a requisição passa |
| [Arquitetura](docs/architecture.md) | Contexto, containers, módulos, Lambdas, recursos e configuração |
| [Fluxos](docs/flows.md) | Máquina de estado e caminhos de sucesso, falha e reprocessamento |
| [OpenAPI da sample-solution](docs/api/openapi.yaml) | Contrato do `POST /requests` |
| [OpenAPI da receipt-solution](docs/api/receipt-solution.openapi.yaml) | Contrato da consulta e da emissão manual de comprovantes |
| [AsyncAPI da sample-solution](docs/events/asyncapi.yaml) | Contratos de eventos e mensagens da sample-solution |
| [AsyncAPI da receipt-solution](docs/events/receipt-solution.asyncapi.yaml) | Evento consumido da sample-solution e `ReceiptIssued` |
| [Testes](docs/testing.md) | Unitários, contrato, integração com Floci e ponta a ponta |
| [Decisões (ADRs)](docs/README.md#decisões) | Registro das decisões de arquitetura |

## Stack

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

## Estrutura

```text
compose.yaml            Floci na porta 4566, com acesso ao Docker para executar as Lambdas, e a
                        console floci-ui na porta 4500
package.json            Serverless Framework e plugins (pnpm)
pom.xml                 POM raiz neutro: Java 21, versões de plugins e testcontainers-floci-aws
platform/
  spring-parent/        Parent das soluções Spring: Spring Boot, Spring Cloud, AWS SDK, exclusões de clients HTTP e zip das Lambdas
  quarkus-parent/       Parent das soluções Quarkus: Quarkus LTS, Quarkus Amazon Services e zip por módulo
  spring-aws/           Spring: autoconfiguração dos clients do AWS SDK v2 (cada client sobe se o SDK estiver no módulo)
sample-solution/        Spring
  serverless.yml        Lambdas, API, máquina de estado e recursos do exemplo
  api/                  Lambda HTTP: Spring MVC (@RestController) via serverless-java-container
  functions/            Lambdas dos passos: Spring Cloud Function (Function<In, Out>)
  e2e/                  Testes ponta a ponta contra a stack no Floci (perfil -Pe2e)
receipt-solution/       Quarkus, acionada pelo RequestCompleted da sample-solution
  serverless.yml        Lambdas, API, máquina de estado com gatilho no barramento da sample, tabela e barramento
  api/                  Lambda HTTP: Quarkus REST via quarkus-amazon-lambda-rest (consulta e emissão manual)
  functions/            Lambdas issue e announce: quarkus-amazon-lambda (RequestHandler com @Named)
  e2e/                  Testes ponta a ponta entre as duas soluções (perfil -Pe2e)
```

Uma solução nova escolhe o parent do framework em `platform/` e ganha o próprio `serverless.yml`
([ADR 0010](docs/decisions/0010-raiz-neutra-e-parents-por-framework.md)).

## Pré-requisitos

- JDK 21 e Maven 3.9
- Docker com o daemon rodando
- Node.js e pnpm
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

O `mvn package` gera os zips das Lambdas. Os módulos Spring têm as classes na raiz e as dependências
em `lib/`: `sample-solution/api/target/api-lambda.zip` e
`sample-solution/functions/target/functions-lambda.zip`. Nos módulos Quarkus, a extensão de Lambda
gera o `function.zip`, e o build monta a partir dele um zip com nome único por módulo:
`receipt-solution/api/target/receipt-api-lambda.zip` e
`receipt-solution/functions/target/receipt-functions-lambda.zip`.

## Testes

```bash
mvn test          # unitários e contrato, sem Docker
mvn verify        # mais a integração com Floci via Testcontainers (precisa de Docker)
pnpm test:e2e     # build, deploy das duas soluções no Floci e testes ponta a ponta
```

Detalhes em [docs/testing.md](docs/testing.md).

## Deploy no Floci

```bash
pnpm deploy:local     # mvn package e deploy da sample-solution e depois da receipt-solution
```

A ordem importa: a regra que aciona a `receipt-solution` fica no barramento da `sample-solution`,
então a sample é implantada antes. Para implantar uma solução só, depois do `mvn package`, use
`pnpm deploy:sample` ou `pnpm deploy:receipt`.

O stage `local` ativa o `serverless-localstack`, que direciona o deploy para `localhost:4566`.
Ao final, o Serverless mostra o endpoint da API, no formato
`http://localhost:4566/restapis/<api-id>/local/_user_request_`.

## Fluxo de exemplo

`POST /requests` grava o payload no S3 e inicia a máquina de estado `sample-solution-local-request-flow`:

1. `validate`: lê o limite de tamanho no SSM (`4096` bytes), a chave de assinatura no Secrets Manager
   e o payload no S3; rejeita payload vazio ou acima do limite e assina o conteúdo com HMAC.
2. `persist`: grava o resultado no DynamoDB.
3. `notify`: publica no SNS e emite o evento `RequestCompleted` no EventBridge; uma regra entrega o
   evento na fila SQS de auditoria.

Qualquer falha nos passos envia a mensagem para a DLQ (SQS). A função `reprocess`, acionada pelo
EventBridge Scheduler a cada 5 minutos, reinicia as execuções da DLQ e descarta a mensagem após
3 tentativas.

O mesmo `RequestCompleted` inicia a máquina de estado `receipt-solution-local-receipt-flow`, em
Quarkus: `issue` grava o comprovante na tabela `receipt-solution-local-receipts` e `announce`
publica `ReceiptIssued` no barramento `receipt-solution-local-events`
([ADR 0011](docs/decisions/0011-integracao-entre-solucoes-por-eventbridge.md)). A API da
`receipt-solution` consulta o comprovante de um pedido e emite um manualmente:

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
estado e os recursos continuam no Floci, então o deploy acima precisa existir.

```bash
cd sample-solution
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
pnpm sls offline --stage local

curl -X POST -H "Content-Type: application/json" -d '{"origem":"offline"}' \
  http://localhost:3000/local/requests
```

## Remover o deploy do Floci

A `receipt-solution` sai primeiro, porque a regra dela fica no barramento da `sample-solution`. O
bucket de payloads precisa estar vazio antes da remoção da sample:

```bash
(cd receipt-solution && pnpm sls remove --stage local)
aws --profile localstack s3 rm s3://sample-solution-local-payloads --recursive
(cd sample-solution && pnpm sls remove --stage local)
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
  reinícios do container. Para começar do zero, use `docker compose down -v` e refaça o deploy.
- Quando o `serverless-localstack` está ativo, o Serverless v4 exporta `AWS_ENDPOINT_URL_S3` apontando
  para o host do LocalStack, e o `serverless-offline` repassa essa variável ao container. Por isso o
  `serverless.yml` declara `AWS_ENDPOINT_URL_S3` apontando para o Floci.
- Os parâmetros de ambiente (`params`) do `serverless.yml` existem só para o stage `local`.
- O `pnpm-workspace.yaml` nega os scripts de instalação de `serverless`, `aws-sdk`, `es5-ext` e
  `esbuild`; nenhum deles é necessário para o uso atual.
- Os pacotes das Lambdas têm entre 21 e 43 MiB. O `aws-crt` entra só com os nativos de Linux, para
  x86_64 e arm64; os testes usam o jar completo, com os nativos de todas as plataformas
  ([ADR 0012](docs/decisions/0012-aws-crt-com-nativos-linux-no-pacote.md)).
- O EventBridge Scheduler dispara o `reprocess` no Floci a cada 5 minutos (observado nos logs da
  função às 20:08:56 e 20:14:02 de 10/11/2026); para testar sem esperar, invoque a função direto.
