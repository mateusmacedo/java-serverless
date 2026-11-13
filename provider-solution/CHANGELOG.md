# Changelog: provider-solution

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [2.0.0] - 2026-11-13

Primeira versão, publicada com a versão 2.0.0 do repositório.

### Adicionado

- Workers de enriquecimento do veículo e do condutor, com reuso (`HIT`, `JOIN` e `MISS`), liderança, lease
  e fencing, e quota por provedor.
- Workers de seguradora síncrona (alpha) e assíncrona (beta, com `202` e callback assinado).
- API `POST /v1/providers/{provider}/callbacks` com inbox idempotente
  ([OpenAPI](../contracts/provider-solution/openapi.yaml)).
- Outbox publicada por DynamoDB Streams no barramento da plataforma
  ([ADR 0013](../docs/decisoes/0013-barramento-da-plataforma.md),
  [ADR 0015](../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md)).
- Reconciliação automática e manual de consultas, DLQs com reprocessamento e limite de tentativas, alarmes
  e métricas EMF ([ADR 0007](../docs/decisoes/0007-dlq-e-reprocessamento.md)).
- Simuladores dos fornecedores de enriquecimento e das seguradoras, que leem o catálogo do laboratório.
- Stage `dev` preparado para a AWS, com IAM próprio da API de callbacks e dos simuladores
  ([guia de implantação](../docs/implantacao-aws-dev.md)).

[2.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v2.0.0
