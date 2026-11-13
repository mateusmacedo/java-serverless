# Changelog: backoffice

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [2.0.0] - 2026-11-13

Primeira versão. A versão do pacote passa a acompanhar a do repositório.

### Adicionado

- Painel Angular 22 com o shell do CoreUI para o corretor e o operador: lista, nova cotação,
  acompanhamento com polling, comparação, complementação por versão, seleção e ocorrências
  ([ADR 0016](../docs/decisoes/0016-backoffice-angular-e-login-cognito.md)).
- Cliente da API gerado pelo Hey API a partir do [`contracts/openapi.yaml`](../contracts/openapi.yaml).
- Login no user pool da `quote-solution`, com guards por permissão.
- Testes unitários com Vitest e jornadas do PRD de frontend com Playwright, respondendo com as fixtures de
  [`contracts/fixtures/`](../contracts/fixtures).

[2.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v2.0.0
