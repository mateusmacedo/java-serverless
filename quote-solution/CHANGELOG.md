# Changelog: quote-solution

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [2.0.0] - 2026-11-13

Primeira versão, publicada com a versão 2.0.0 do repositório.

### Adicionado

- API de produto em Quarkus REST: criação de cotação e de nova versão, acompanhamento, ofertas e seleção,
  com idempotência por `Idempotency-Key`, paginação por cursor e o contrato
  [`contracts/openapi.yaml`](../contracts/openapi.yaml).
- Máquina de estado `quote-flow`: elegibilidade (`elig-1`), seleção de seguradoras (`sel-1`), espera do
  enriquecimento e das seguradoras pelo task token e consolidação do outcome pela precedência do PRD
  ([ADR 0014](../docs/decisoes/0014-cotacao-em-duas-solucoes-e-task-token.md)).
- Tabela própria com outbox publicada por DynamoDB Streams no barramento da plataforma
  ([ADR 0013](../docs/decisoes/0013-barramento-da-plataforma.md),
  [ADR 0015](../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md)).
- Catálogo do laboratório em `/v1/catalog/*`, ocorrências operacionais e reconciliação manual de consultas
  em `/v1/operations/*`.
- `reconciler` agendado, DLQs com limite de tentativas, alarmes e métricas EMF.
- Autenticação por access token do user pool da stack, com organização e permissões por grupo.
- Stage `dev` preparado para a AWS, com IAM por função e ações nos alarmes
  ([guia de implantação](../docs/implantacao-aws-dev.md)).
- Teste ponta a ponta `QuoteFlowE2E` contra as stacks implantadas no Floci.

[2.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v2.0.0
