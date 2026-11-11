# Testes

Os testes seguem a pirâmide: muitos testes rápidos sem infraestrutura na base, alguns contra a AWS
emulada no meio e poucos contra a stack implantada no topo. Cada nível tem seu comando e sua
convenção de nome, para o build do dia a dia não depender de deploy.

| Nível | Convenção | Comando | Precisa de | Tempo aproximado |
|---|---|---|---|---|
| Unitário e contrato | `*Test` | `mvn test` | Nada além do JDK | Segundos |
| Integração com Floci | `*IT` | `mvn verify` | Docker | Cerca de 30 s |
| Ponta a ponta | `*E2E` | `pnpm test:e2e` | Docker, Floci do `compose.yaml` no ar e login no Serverless | Cerca de 1 min |

O `mvn verify` roda os dois primeiros níveis. O módulo `sample-solution/e2e` só entra no build com o
perfil `-Pe2e`.

## Unitários

Ferramentas: JUnit 5, Mockito e AssertJ, nas versões do `spring-boot-starter-test`.

| Módulo | Classe | O que cobre |
|---|---|---|
| `shared` | `AwsClientsAutoConfigurationTest` | Um client por SDK presente, client omitido quando o SDK falta, client da aplicação preservado, URL do S3 em virtual-host por padrão e em path-style quando configurado |
| `functions` | `ValidateFunctionTest` | Assinatura HMAC conferida com o vetor de teste público do HMAC-SHA256, limite exato, payload acima do limite e payload vazio |
| `functions` | `PersistFunctionTest` | Tabela e atributos gravados no DynamoDB |
| `functions` | `NotifyFunctionTest` | Mensagem no SNS, evento compatível com a regra do EventBridge e erro quando o EventBridge rejeita o evento |
| `functions` | `ReprocessFunctionTest` | Reinício com `requestId-2`, descarte no limite, lote completo, fila e tamanho de lote configurados |
| `functions` | `ReprocessCommandTest` | Ajuste do lote para a faixa de 1 a 10 |
| `api` | `RequestControllerTest` | `202` com o upload e a execução conferidos; `415` e `400` sem chamar a AWS |

Os testes das functions simulam os clients do SDK com `Answers.CALLS_REAL_METHODS`: o código chama a
sobrecarga com `Consumer`, que monta o request e chama a sobrecarga com o request pronto, que é a
simulada. Por isso essas classes usam o modo leniente do Mockito; as verificações de cada teste
continuam explícitas.

## Contrato

`RequestControllerContractTest` confere requisições e respostas do controller contra o
[OpenAPI](api/openapi.yaml), com o `swagger-request-validator-mockmvc`. Nos casos `400` e `415` a
requisição é inválida de propósito, então só a resposta é conferida.

O teste pega divergência de verdade: com a resposta `415` removida do OpenAPI, ele falha com
`validation.response.status.unknown`. Ao mudar a API, atualize o contrato no mesmo commit.

## Integração com Floci

Cada módulo sobe um único Floci (`floci/floci:2.4.0`) pelo módulo oficial
[`testcontainers-floci`](https://github.com/floci-io/testcontainers-floci), na linha 2.x compatível com o
Testcontainers 2.0 do Spring Boot 4.0. A classe base `FlociIntegrationTest` passa endpoint, região e
credenciais para as propriedades de sistema do AWS SDK (`aws.endpointUrl`, `aws.region`,
`aws.accessKeyId`, `aws.secretAccessKey`). Assim os testes usam a autoconfiguração real do `shared`.

| Classe | O que cobre |
|---|---|
| `RequestFlowFunctionsIT` | As quatro funções com S3, SSM, Secrets Manager, DynamoDB, SNS com assinante SQS, EventBridge com regra para a fila de auditoria, SQS e Step Functions reais |
| `RequestControllerFlociIT` | A aplicação HTTP completa gravando no S3 e iniciando uma execução real |

Cada teste cria os recursos de que precisa antes do contexto Spring, com os mesmos papéis do
`serverless.yml`. A imagem do Floci vem da propriedade `floci.image` do POM raiz.

## Ponta a ponta

`RequestFlowE2E` chama a API implantada no Floci como um cliente faria:

- **Sucesso:** `202`, execução `SUCCEEDED`, item no DynamoDB com assinatura e tamanho, evento
  `RequestCompleted` na fila de auditoria.
- **Falha:** payload acima do limite, execução `FAILED`, reprocessamento até `requestId-3`, mensagem
  descartada da DLQ e nenhuma execução `requestId-4`.

O Scheduler também roda o `reprocess` a cada 5 minutos, então o teste confere o estado final de cada
fluxo, e não contagens exatas de reinício.

```bash
docker compose up -d
pnpm test:e2e
```

O `test:e2e` empacota os módulos sem rodar testes, faz o deploy no stage `local` e executa
`mvn verify -Pe2e -pl sample-solution/e2e`. Para testar só contra uma stack já implantada:

```bash
mvn -B verify -Pe2e -pl sample-solution/e2e
```

As propriedades `e2e.endpoint` (padrão `http://localhost:4566`) e `e2e.stage` (padrão `local`) do
`sample-solution/e2e/pom.xml` apontam para outra stack.

## Limites conhecidos

- A resposta `500` da API não tem teste.
- O suporte do `swagger-request-validator` ao OpenAPI 3.1 é parcial; o contrato atual passou, mas
  recursos específicos do 3.1 podem não ser validados.
- O formato da mensagem do SNS é conferido só no teste de integração, onde há um assinante SQS; a
  stack do exemplo não tem assinante.
