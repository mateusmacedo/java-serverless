# 0003. Spring Boot 3.5 pela adoção de mercado

- Status: substituída por [0009](0009-spring-boot-4-0.md)
- Data: 2026-11-10

## Contexto

Em novembro de 2026 havia três linhas candidatas: Spring Boot 3.5 (suporte gratuito encerrado em
30/06/2026), 4.0 (dupla GA com o Spring Cloud 2025.1, suporte gratuito até 31/12/2026) e 4.1 (suporte
até 31/07/2027, mas com o Spring Cloud 2026.0 ainda em RC). O critério definido foi usar a versão mais
adotada pelo mercado.

## Decisão

Spring Boot 3.5.16, com Spring Cloud 2025.0.3 (Spring Cloud Function 4.3.4), a linha oficial do
Spring Cloud para o Boot 3.5.

A fonte é o relatório 2026 State of Open Source da OpenLogic/Perforce, publicado em 29/05/2026:
52,05% dos usuários de Spring Boot estavam na 3.5 e 27,40% na 2.7
([OpenLogic](https://www.openlogic.com/blog/java-ecosystem-trends)).

## Consequências

- As bibliotecas usadas têm compatibilidade conhecida com a linha 3.5, inclusive o
  `aws-serverless-java-container-springboot3`.
- Não há correções de segurança gratuitas da linha 3.5 desde 30/06/2026; só suporte comercial.
- A migração para a linha 4 continua pendente e fica maior com o tempo.
- O dado de mercado vem de uma única pesquisa, com cerca de cinco meses na data da decisão.

## Alternativas consideradas

- Spring Boot 4.0.9 com Spring Cloud 2025.1.4: dupla GA mais nova, suporte gratuito por poucas semanas.
- Spring Boot 4.1.2 com Spring Cloud 2026.0 em RC: suporte mais longo, com dependências pré-lançamento.
