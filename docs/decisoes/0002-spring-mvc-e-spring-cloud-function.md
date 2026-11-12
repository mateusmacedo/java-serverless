# 0002. Spring MVC na API e Spring Cloud Function nos passos

- Status: aceita
- Data: 2026-11-10

## Contexto

A solução tem dois tipos de Lambda: a entrada HTTP, chamada pelo API Gateway, e os passos da
máquina de estado e o reprocessamento, chamados pelo Step Functions e pelo Scheduler. Os passos não
recebem requisições HTTP, então não passam por controller. Uma primeira versão usou o modelo
reativo (WebFlux e `Mono`) e foi descartada em favor do modelo Spring convencional.

## Decisão

- **API:** Spring MVC com `@RestController`, executado na Lambda pelo `aws-serverless-java-container-springboot3`
  2.1.7. O handler é `StreamLambdaHandler`, que mantém o contexto Spring estático entre invocações.
  O Tomcat é excluído, porque a biblioteca substitui o servidor embutido.
- **Passos e reprocessamento:** Spring Cloud Function 4.3.4 com `Function<In, Out>` imperativas e o
  handler genérico `FunctionInvoker`. As quatro funções ficam no mesmo zip; cada Lambda escolhe a
  sua por `SPRING_CLOUD_FUNCTION_DEFINITION`, e a aplicação é localizada por `MAIN_CLASS`.
- O módulo `functions` liga `spring.main.lazy-initialization`, para que cada Lambda crie só os beans
  da função que executa.

## Consequências

- Cada tipo de Lambda usa o adaptador oficial feito para ele.
- Há dois estilos de handler no mesmo projeto, separados nos módulos `api` e `functions`.
- Os tipos de entrada e saída das funções são records serializados em JSON pelo Spring Cloud Function.

## Alternativas consideradas

- Tudo em Spring Cloud Function, inclusive a rota HTTP: um modelo só, mas sem `@RestController`.
- `RequestHandler` da AWS com contexto Spring criado manualmente: mais código de integração por handler.
- WebFlux reativo com `Mono` nos passos: implementado e validado, mas removido na mudança para o modelo
  convencional.
