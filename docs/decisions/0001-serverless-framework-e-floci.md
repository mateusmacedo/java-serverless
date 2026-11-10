# 0001. Serverless Framework v4 com Floci como emulador local

- Status: aceita
- Data: 2026-11-10

## Contexto

O ambiente precisa de uma ferramenta de deploy para Lambda e Step Functions e de um jeito de
executar tudo localmente, sem conta AWS. Há acesso pago ao Serverless Framework. A edição
comunitária do LocalStack foi encerrada em março de 2026 e passou a exigir token de autenticação
([README do Floci](https://github.com/floci-io/floci)).

## Decisão

- Deploy com o Serverless Framework 4.46.0, instalado como dependência do projeto (`pnpm sls`).
- Step Functions declarado pelo plugin `serverless-step-functions`, porque o v4 não tem suporte nativo.
- Emulação local com o Floci 2.4.0 (licença MIT), que se apresenta como substituto direto do LocalStack
  na porta 4566 e executa as Lambdas em containers reais.
- Plugin `serverless-localstack` ativo só no stage `local`, para direcionar o deploy ao Floci.
- Plugin `serverless-offline` com Docker, na rede `floci`, para testar a rota HTTP sem passar pelo
  API Gateway emulado.

## Consequências

- O mesmo `serverless.yml` serve ao Floci e, com outros `params`, à AWS.
- O Serverless v4 exige login válido até para `package`; sem ele, o comando falha com `Forbidden`.
- O Floci roda em memória: reiniciar o container apaga os recursos e exige novo deploy.
- A combinação Serverless v4, `serverless-localstack` e Floci não é documentada oficialmente; ela foi
  validada neste projeto com deploy, execução e remoção da stack.
- O Serverless v4 injeta variáveis próprias do LocalStack quando o plugin está ativo
  ([ADR 0008](0008-aws-endpoint-url-s3-no-offline.md)).

## Alternativas consideradas

- LocalStack: passou a exigir token de autenticação na edição gratuita.
- AWS SAM CLI: não estava no ambiente e trocaria a ferramenta de deploy já licenciada.
- Conta AWS de desenvolvimento: custo e dependência de rede para cada teste.
