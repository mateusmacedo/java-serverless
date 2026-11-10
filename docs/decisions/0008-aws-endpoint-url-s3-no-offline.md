# 0008. `AWS_ENDPOINT_URL_S3` explícito para o serverless-offline

- Status: aceita
- Data: 2026-11-10

## Contexto

No `serverless-offline`, a rota `POST /requests` respondia `500`: o client do S3 tentava conectar em
`s3.localhost.localstack.cloud:4566`, que resolve para `127.0.0.1` dentro do container da Lambda.

A causa está no próprio Serverless v4. Quando o `serverless-localstack` está ativo no stage, a função
`configureLocalstackAWSEndpoint` do `sf-core.js` define `AWS_ENDPOINT_URL=http://localhost:4566` e
`AWS_ENDPOINT_URL_S3=http://s3.localhost.localstack.cloud:4566` no processo, se ainda não existirem. O
`serverless-offline` repassa essas variáveis ao container. O `AWS_ENDPOINT_URL` já era sobrescrito
pelo `serverless.yml`, mas o `AWS_ENDPOINT_URL_S3` não, e a variável específica do serviço tem
prioridade sobre a geral no AWS SDK.

## Decisão

O `serverless.yml` declara `AWS_ENDPOINT_URL_S3: ${param:awsEndpointUrl}`, com o mesmo valor do
`AWS_ENDPOINT_URL` (`http://floci:4566` no stage `local`).

## Consequências

- A rota funciona no offline e o deploy no Floci continua igual, porque as variáveis da função têm
  prioridade sobre as do processo.
- O comportamento depende de um detalhe interno do Serverless v4; uma atualização do framework pode
  mudar os nomes ou os valores injetados.
- Se outro service ganhar variável específica (`AWS_ENDPOINT_URL_<SERVICO>`) no futuro, ele precisará
  do mesmo tratamento.

## Alternativas consideradas

- Exportar `AWS_ENDPOINT_URL_S3` no terminal antes do comando: afetaria também o deploy feito a partir
  do host, onde o nome `floci` não resolve.
- Desativar o `serverless-localstack` durante o offline: o Serverless deixaria de resolver as variáveis
  de conta e região contra o Floci.
