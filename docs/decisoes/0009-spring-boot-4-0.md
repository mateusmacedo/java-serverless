# 0009. Spring Boot 4.0 com Jackson 3

- Status: aceita
- Data: 2026-11-10
- Substitui: [0003](0003-spring-boot-3-5.md)

## Contexto

O [ADR 0003](0003-spring-boot-3-5.md) escolheu o Spring Boot 3.5.16 pela adoção de mercado, aceitando
que a linha estava sem correções gratuitas desde 30/06/2026. A decisão foi revista com outro critério:
ficar numa linha com suporte gratuito. Em 10/11/2026 o quadro era este:

| Linha | Suporte gratuito até | Suporte comercial até | Spring Cloud |
|---|---|---|---|
| 3.5 | 30/06/2026 | 30/06/2032 | 2025.0.3, versão final |
| 4.0 | 31/12/2026 | 31/12/2027 | 2025.1.4, versão final |
| 4.1 | 31/07/2027 | 31/07/2028 | 2026.0.0-RC2, sem versão final |

Nenhuma linha do Spring Boot leva o rótulo LTS ([endoflife.date](https://endoflife.date/spring-boot)).
O Spring Cloud Function, que executa as Lambdas dos passos, só terá versão final para o Boot 4.1
quando o Spring Cloud 2026.0 sair.

## Decisão

Spring Boot 4.0.9 com Spring Cloud 2025.1.4 (Spring Cloud Function 5.0.5),
`aws-serverless-java-container-springboot4` 3.0.5 e `testcontainers-floci` 2.17.0, que usa o
Testcontainers 2.0.5, o mesmo gerenciado pelo Boot. O JSON passa para o Jackson 3 (`tools.jackson`),
padrão do Boot 4 e já usado pelo container e pelo Spring Cloud Function.

A passagem para o 4.1 fica para quando o `spring-cloud-dependencies` 2026.0.0 sair em versão final. É
um passo dentro da mesma major, sem a troca de Jackson e de starters feita agora.

## Consequências

- O suporte gratuito vai até 31/12/2026. A migração para o 4.1 precisa acontecer antes disso, ou o
  projeto volta a ficar sem correções gratuitas.
- O Jackson 3 lança exceções unchecked (`JacksonException`). Os blocos que convertiam
  `JsonProcessingException` em `UncheckedIOException` saíram.
- Starters e módulos de teste mudaram de nome: `spring-boot-starter-webmvc`,
  `spring-boot-starter-jackson` e `spring-boot-starter-webmvc-test`. `@WebMvcTest` e
  `@AutoConfigureMockMvc` estão em `org.springframework.boot.webmvc.test.autoconfigure`.
- O `spring-cloud-function-adapter-aws` 5.x declara o `spring-boot-starter-webflux`, mas nenhuma
  classe dele usa o WebFlux. O módulo `functions` exclui o starter; sem a exclusão, o zip passava de
  62 MB para 79 MB, com Reactor Netty e os binários nativos do QUIC.
- O container 3.x traz o Afterburner do Jackson 3, que depende do byte-buddy: o zip da `api` passou
  de 49 MB para 56 MB. Na `api`, `spring.jackson.find-and-add-modules` fica desligado para o
  Afterburner não entrar no mapper da aplicação, onde emitia um aviso para cada record.
- Os ADRs [0002](0002-spring-mvc-e-spring-cloud-function.md) e [0004](0004-sem-graalvm-native.md)
  citam versões da linha 3 (container 2.1.7, Spring Cloud Function 4.3.4). As decisões deles
  continuam valendo; as versões atuais estão aqui e no README.

## Alternativas consideradas

- Spring Boot 4.1.2 com Spring Cloud 2026.0.0-RC2: suporte gratuito até 31/07/2027, com o Spring
  Cloud Function ainda em versão candidata.
- Manter o Spring Boot 3.5.16: suporte comercial até 30/06/2032, sem correções gratuitas.
