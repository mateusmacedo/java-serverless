# Changelog: sample-solution

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [1.0.0] - 2026-11-11

Primeira versão estável.

### Adicionado

- API `POST /requests` em Spring MVC sobre o `aws-serverless-java-container`: grava o payload no S3 e
  inicia a máquina de estado `requestFlow` só com a chave do objeto
  ([ADR 0002](../docs/decisoes/0002-spring-mvc-e-spring-cloud-function.md),
  [ADR 0006](../docs/decisoes/0006-payload-no-s3.md)).
- Passos `validate`, `persist` e `notify` como Spring Cloud Functions, orquestrados pelo Step Functions.
  Uma falha em qualquer passo manda a requisição para a DLQ
  ([ADR 0007](../docs/decisoes/0007-dlq-e-reprocessamento.md)).
- Passo `notify` publica no tópico SNS e emite o evento `RequestCompleted` no barramento do EventBridge
  da solução, que aciona a `receipt-solution`
  ([ADR 0011](../docs/decisoes/0011-integracao-entre-solucoes-por-eventbridge.md)).
- Função `reprocess`, agendada a cada 5 minutos pelo EventBridge Scheduler: lê a DLQ e reinicia a
  máquina de estado, descartando a mensagem depois do limite de tentativas.
- Recursos declarados no `serverless.yml`: bucket S3, tabela DynamoDB, filas SQS de DLQ e de auditoria,
  tópico SNS, barramento e regra do EventBridge, parâmetro SSM e segredo do Secrets Manager.
- Spring Boot 4.0 com Jackson 3 ([ADR 0009](../docs/decisoes/0009-spring-boot-4-0.md)).
- Testes unitários e de contrato OpenAPI (`mvn test`), de integração com o Floci via Testcontainers
  (`mvn verify`) e ponta a ponta contra a stack implantada (perfil `e2e`).

[1.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v1.0.0
