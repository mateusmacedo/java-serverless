# 0010. Raiz neutra e um parent por framework

- Status: aceita
- Data: 2026-11-10

## Contexto

O repositório passou a acomodar soluções em Spring e em Quarkus, escolhidas conforme o propósito de
cada uma. Até aqui, o `pom.xml` da raiz herdava do `spring-boot-starter-parent` e importava os BOMs
do Spring Cloud e do AWS SDK. Um módulo Quarkus abaixo dele herdaria as versões do Spring.

Os dois frameworks também fixam versões diferentes do que têm em comum. Em 10/11/2026:

| Peça | Spring Boot 4.0.9 | Quarkus 3.40.1.2 (LTS) |
|---|---|---|
| AWS SDK for Java v2 | 2.57.12 (escolha do projeto) | 2.54.13 (BOM do Quarkus Amazon Services) |
| Jackson | 3.1.7 | 2.21.8 |
| JUnit Jupiter | 6.0.3 | 6.1.3 |
| Testcontainers | 2.0.5 | 2.0.5 |

## Decisão

- O `pom.xml` da raiz não herda de nenhum framework. Ele guarda só o que vale para os dois: Java 21,
  encoding, versões e configuração dos plugins Maven (com `parameters=true` no compilador e as
  execuções do failsafe que vinham do `spring-boot-starter-parent`) e o `testcontainers-floci`.
- `platform/spring-parent` importa o BOM do Spring Boot antes dos demais, para manter a precedência
  que ele tinha como parent, e concentra Spring Cloud, AWS SDK, as exclusões de clients HTTP e o
  empacotamento em zip para Lambda, com o descritor inline no `maven-assembly-plugin`.
- `platform/quarkus-parent` importa o BOM do Quarkus e o do Quarkus Amazon Services e configura o
  `quarkus-maven-plugin`. O Quarkus fica na linha LTS 3.40, com suporte até 30/09/2027.
- Cada solução escolhe o parent do seu framework. A `sample-solution` usa o `spring-parent`, e a
  `receipt-solution` usa o `quarkus-parent`.
- O antigo módulo `shared`, que só tem a autoconfiguração Spring dos clients AWS, passou para
  `platform/spring-aws` (pacote `com.mmda.serverless.platform.spring.aws`). Na raiz, o nome genérico
  sugeria uma biblioteca comum aos dois frameworks.
- No Quarkus, os clients do AWS SDK vêm das extensões do Quarkus Amazon Services com
  `sync-client.type=aws-crt`, o mesmo client HTTP do `spring-aws` ([ADR 0005](0005-clients-sincronos-com-crt.md)).
  Endpoint, região e credenciais seguem as cadeias padrão do SDK, como no Spring.

## Consequências

- O lado Spring não mudou: as dependências resolvidas dos quatro módulos, os plugins efetivos e a
  lista de jars dos zips são idênticos aos de antes da mudança.
- Versões que só um framework usa não sobem para a raiz. Atualizar o AWS SDK de um lado não arrasta
  o outro.
- Os módulos Quarkus usam `artifactId` com o prefixo da solução (`receipt-functions`), porque o
  `groupId` é o mesmo da `sample-solution`.
- As extensões de Lambda do Quarkus sempre geram `target/function.zip`, e o Serverless envia os
  artefatos ao bucket de deploy pelo nome do arquivo. Com a API e as funções da `receipt-solution`
  na mesma stack, o zip da API sobrescreveu o das funções no primeiro deploy. O `quarkus-parent`
  copia o zip para `<artifactId>-lambda.zip`, e cada módulo declara o `maven-antrun-plugin` depois
  do `quarkus-maven-plugin`.
- As Lambdas Quarkus também rodam em modo JVM no runtime `java21` ([ADR 0004](0004-sem-graalvm-native.md)).
  No Floci, elas subiram em cerca de 0,8 s, contra 1,7 a 1,9 s das Lambdas Spring; é uma medida única,
  sem valor de benchmark. A imagem nativa continua sendo a alternativa para cold start menor.

## Alternativas consideradas

- Manter o `spring-boot-starter-parent` como parent do `spring-parent`: deixa o lado Spring mais
  idiomático, mas duplica Java, plugins e Floci nos dois parents.
- Converter a `sample-solution` numa solução mista: foi descartado, porque cada solução deve usar um
  framework só e conversar com as outras por eventos ([ADR 0011](0011-integracao-entre-solucoes-por-eventbridge.md)).
