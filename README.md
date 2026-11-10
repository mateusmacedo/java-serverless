# mmda-serverless

Ambiente para projetar soluções serverless com AWS Step Functions e AWS Lambda em Java,
com deploy pelo Serverless Framework e emulação local da AWS pelo [Floci](https://github.com/floci-io/floci).

## Documentação

| Documento | Conteúdo |
|---|---|
| [Índice da documentação](docs/README.md) | Mapa dos documentos, convenções e como validar |
| [Guia do código](docs/code-walkthrough.md) | Por onde começar a ler: como o código sobe e por onde a requisição passa |
| [Arquitetura](docs/architecture.md) | Contexto, containers, módulos, Lambdas, recursos e configuração |
| [Fluxos](docs/flows.md) | Máquina de estado e caminhos de sucesso, falha e reprocessamento |
| [OpenAPI](docs/api/openapi.yaml) | Contrato da API HTTP |
| [AsyncAPI](docs/events/asyncapi.yaml) | Contratos de eventos e mensagens |
| [Testes](docs/testing.md) | Unitários, contrato, integração com Floci e ponta a ponta |
| [Decisões (ADRs)](docs/README.md#decisões) | Registro das decisões de arquitetura |

## Stack

| Peça | Versão |
|---|---|
| Java (runtime `java21` da Lambda) | 21 |
| Maven | 3.9 |
| Spring Boot | 3.5.16 |
| Spring Cloud / Spring Cloud Function | 2025.0.3 / 4.3.4 |
| aws-serverless-java-container-springboot3 | 2.1.7 |
| AWS SDK for Java v2 (clients síncronos com `AwsCrtHttpClient`) | 2.57.12 |
| Serverless Framework | 4.46.0 |
| serverless-step-functions / serverless-localstack / serverless-offline | 3.31.2 / 1.4.0 / 14.8.4 |
| Floci | 2.4.0 |

## Estrutura

```text
compose.yaml            Floci na porta 4566, com acesso ao Docker para executar as Lambdas
package.json            Serverless Framework e plugins (pnpm)
pom.xml                 POM pai: versões, BOMs e exclusão dos clientes HTTP Apache e Netty do SDK
shared/                 Autoconfiguração dos clients do AWS SDK v2 (cada client sobe se o SDK estiver no módulo)
sample-solution/
  serverless.yml        Lambdas, API, máquina de estado e recursos do exemplo
  api/                  Lambda HTTP: Spring MVC (@RestController) via serverless-java-container
  functions/            Lambdas dos passos: Spring Cloud Function (Function<In, Out>)
  e2e/                  Testes ponta a ponta contra a stack no Floci (perfil -Pe2e)
```

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

O `mvn package` gera um zip por módulo, com as classes na raiz e as dependências em `lib/`:
`sample-solution/api/target/api-lambda.zip` e `sample-solution/functions/target/functions-lambda.zip`.

## Testes

```bash
mvn test          # unitários e contrato, sem Docker
mvn verify        # mais a integração com Floci via Testcontainers (precisa de Docker)
pnpm test:e2e     # build, deploy no Floci e testes ponta a ponta
```

Detalhes em [docs/testing.md](docs/testing.md).

## Deploy no Floci

```bash
cd sample-solution
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
pnpm sls deploy --stage local
```

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

O bucket de payloads precisa estar vazio antes da remoção da stack:

```bash
aws --profile localstack s3 rm s3://sample-solution-local-payloads --recursive
cd sample-solution && pnpm sls remove --stage local
```

## Perfis do AWS CLI

| Perfil | Destino | Autenticação |
|---|---|---|
| `localstack` | Floci (`localhost:4566`, `us-east-1`) | Chaves fixas de teste |
| `personal` | Conta pessoal (`us-east-1`) | `aws login --profile personal --region us-east-1` |
| `jusdocs`, `jusdocs-dev`, `jusdocs-prod` | Contas da JusDocs | SSO (IAM Identity Center) |

Não há perfil `default`: todo comando precisa de `--profile` ou `AWS_PROFILE`.

## Pontos de atenção

- O Floci roda em modo memória: se o container reiniciar, os recursos somem e é preciso refazer o deploy.
- Quando o `serverless-localstack` está ativo, o Serverless v4 exporta `AWS_ENDPOINT_URL_S3` apontando
  para o host do LocalStack, e o `serverless-offline` repassa essa variável ao container. Por isso o
  `serverless.yml` declara `AWS_ENDPOINT_URL_S3` apontando para o Floci.
- Os parâmetros de ambiente (`params`) do `serverless.yml` existem só para o stage `local`.
- O `pnpm-workspace.yaml` nega os scripts de instalação de `serverless`, `aws-sdk`, `es5-ext` e
  `esbuild`; nenhum deles é necessário para o uso atual.
- Os pacotes das Lambdas têm entre 51 e 64 MB, dos quais 23 MB são do `aws-crt` completo (libs nativas
  de todas as plataformas). O deploy funciona porque sobe via S3.
- O EventBridge Scheduler dispara o `reprocess` no Floci a cada 5 minutos (observado nos logs da
  função às 20:08:56 e 20:14:02 de 10/11/2026); para testar sem esperar, invoque a função direto.
