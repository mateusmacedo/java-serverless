# 0004. Sem imagem nativa GraalVM

- Status: aceita
- Data: 2026-11-10

## Contexto

O plano inicial previa um perfil Maven `native`, gerando binários GraalVM para o runtime
`provided.al2023`, compilados num container Amazon Linux 2023 com GraalVM CE 21.0.2 (a glibc da
máquina de desenvolvimento, 2.43, é mais nova que a do Lambda, 2.34).

Na época a API usava WebFlux. No `aws-serverless-java-container` 2.1.x (linha do Boot 3), o loop de
runtime customizado só é registrado quando o contexto Spring é servlet: a checagem é
`context instanceof ServletWebServerApplicationContext`, em `AwsSpringWebRuntimeInitializer`. Uma API
WebFlux não subiria como binário nativo sem código de infraestrutura próprio. Diante disso, a decisão
foi abandonar o native e voltar ao modelo Spring convencional.

## Decisão

Todas as Lambdas rodam no runtime gerenciado `java21`, a partir de zips com classes e dependências.

## Consequências

- O build é só Maven, sem container de compilação nem configuração de reflexão para o GraalVM.
- O cold start é o da JVM com Spring Boot. O Floci recebeu `FLOCI_SERVICES_LAMBDA_INIT_TIMEOUT_SECONDS=120`
  porque o padrão de 10 segundos não cobre essa inicialização.
- Com a API agora em Spring MVC (servlet), o caminho nativo voltou a ser tecnicamente suportado pelas
  duas bibliotecas; reabrir essa decisão exige um novo ADR.

## Alternativas consideradas

- Native só nas Lambdas do Spring Cloud Function, com a API em JVM.
- Loop de runtime próprio para a API WebFlux: sem exemplo oficial e com risco alto de incompatibilidade
  com o AOT do Spring.
