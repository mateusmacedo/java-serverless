# Changelog: platform

Todas as mudanças relevantes deste projeto ficam registradas aqui.

O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/) e as versões seguem o
[Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [2.0.0] - 2026-11-13

### Adicionado

- `events`: stack `platform` com o barramento `platform-<stage>-events`, onde a `quote-solution` e a
  `provider-solution` publicam eventos e declaram as regras. As soluções chegam a ele pelo nome, sem
  referência entre stacks ([ADR 0013](../docs/decisoes/0013-barramento-da-plataforma.md)).

### Alterado

- `spring-parent`: exclui também o `apache5-client` de cada service do AWS SDK, que o SDK 2.57 passou a
  trazer em runtime; o pacote segue só com o cliente HTTP do CRT.
- Raiz: o build da IDE (perfil do m2e) grava em `target-ide`, para o servidor Java do VS Code não reescrever
  o `target/classes` durante um build do Maven.
- `spring-parent` e `quarkus-parent` passam a servir só à plataforma de cotação: a `sample-solution` e a
  `receipt-solution` saíram do repositório
  ([ADR 0017](../docs/decisoes/0017-remocao-das-solucoes-iniciais.md)).

## [1.0.0] - 2026-11-11

Primeira versão estável.

### Adicionado

- `spring-parent`: parent Maven das soluções Spring, com as versões do Spring Boot 4.0.9, Spring Cloud
  2025.1.4, AWS SDK 2.57.12 e `aws-serverless-java-container` 3.0.5, e o empacotamento do zip da Lambda
  por um descritor de assembly embutido
  ([ADR 0009](../docs/decisoes/0009-spring-boot-4-0.md),
  [ADR 0010](../docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md)).
- `quarkus-parent`: parent Maven das soluções Quarkus, com o Quarkus 3.40.1.2 e o BOM do Quarkus Amazon
  Services ([ADR 0010](../docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md)).
- `spring-aws`: autoconfiguração dos clients síncronos do AWS SDK com o cliente HTTP do CRT, um para cada
  SDK presente no classpath ([ADR 0005](../docs/decisoes/0005-clients-sincronos-com-crt.md)).
- Zips das Lambdas só com os nativos Linux do `aws-crt`, para x86_64 e arm64
  ([ADR 0012](../docs/decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md)).
- Versão do `openapi-request-validator` gerenciada nos dois parents, para os testes de contrato OpenAPI.

[2.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v2.0.0
[1.0.0]: https://github.com/mateusmacedo/java-serverless/releases/tag/v1.0.0
