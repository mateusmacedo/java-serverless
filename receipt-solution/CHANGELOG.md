# Changelog: receipt-solution

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [1.0.0] - 2026-11-11

Primeira versão estável.

### Adicionado

- Lambdas `issue` e `announce` em Quarkus 3.40 com `quarkus-amazon-lambda`: emitem um comprovante idempotente
  por `requestId` e publicam o evento `ReceiptIssued` no barramento do EventBridge da solução.
- Máquina de estado `receiptFlow`, iniciada pelo EventBridge por uma regra no barramento da
  `sample-solution` para o evento `RequestCompleted`
  ([ADR 0011](../docs/decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)).
- API REST com `quarkus-amazon-lambda-rest`: `GET /receipts/{requestId}` consulta um comprovante e
  `POST /receipts` emite um manualmente pela mesma máquina de estado.
- Recursos declarados no `serverless.yml`: tabela DynamoDB de comprovantes e barramento próprio do
  EventBridge.
- Zip de cada Lambda com nome único por módulo, porque a extensão do Quarkus sempre gera `function.zip`.
- Contratos OpenAPI e AsyncAPI, testes unitários, de contrato e de integração com o Floci, e um teste
  ponta a ponta que atravessa as duas soluções.

[1.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v1.0.0
