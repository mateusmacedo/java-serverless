# Arquitetura

O ambiente serve para projetar soluções serverless orquestradas pelo AWS Step Functions, com
Lambdas em Java 21 escritas em Spring Boot 4.0 ou Quarkus 3.40 (LTS), conforme o propósito de cada
solução. O deploy usa o Serverless Framework v4 e, localmente, o
[Floci](https://github.com/floci-io/floci) emula os serviços da AWS na porta 4566.

Este documento cobre o que vale para todas as soluções: módulos compartilhados, configuração,
empacotamento, ambiente local e permissões. As versões das peças estão no
[README da raiz](../README.md#tecnologias).

## Contexto

A solução de demonstração é a plataforma de cotação de seguros: a `quote-solution` (Quarkus) recebe o
pedido e orquestra a cotação, a `provider-solution` (Spring) fala com os fornecedores, as duas conversam
pelo barramento `platform-<stage>-events` da `platform/events`, e o `backoffice` (Angular) é o painel sobre
a API de produto. O diagrama de contexto está em [Cotação](cotacao.md#diagrama-de-contexto); o detalhe de
recursos, eventos, tabelas, funções e fluxos, no [desenho técnico](plataforma-de-cotacao.md); e o que as
soluções compartilham, na [Plataforma](../platform/docs/README.md).

As soluções iniciais, `sample-solution` e `receipt-solution`, saíram na versão 2.0.0
([ADR 0017](decisoes/0017-remocao-das-solucoes-iniciais.md)); o código delas continua na tag `v1.0.0`.

## Módulos Maven

| Módulo                     | Empacotamento | Responsabilidade                                                                                                                                                                                                                                                                                 |
| -------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `serverless-parent` (raiz) | `pom`         | Neutro: Java 21, versões e configuração dos plugins Maven e `testcontainers-floci-aws` ([ADR 0010](decisoes/0010-raiz-neutra-e-parents-por-framework.md))                                                                                                                                        |
| `platform/spring-parent`   | `pom`         | Importa os BOMs do Spring Boot, do AWS SDK e do Spring Cloud; exclui `apache-client`, `apache5-client` e `netty-nio-client` de cada service do SDK; define o empacotamento em zip para Lambda, herdado pelo módulo que declarar `maven-dependency-plugin` e `maven-assembly-plugin`              |
| `platform/quarkus-parent`  | `pom`         | Importa os BOMs do Quarkus e do Quarkus Amazon Services (extensões e AWS SDK); configura o `quarkus-maven-plugin` e monta o `<artifactId>-lambda.zip` de cada módulo, com o `aws-crt` só nos jars Linux; fixa versões fora do BOM (`aws-crt`, AssertJ e `openapi-request-validator-restassured`) |
| `platform/spring-aws`      | `jar`         | Só para soluções Spring: autoconfiguração dos clients síncronos do SDK, cada um com o próprio `AwsCrtHttpClient` e criado só quando o SDK correspondente está no classpath ([ADR 0005](decisoes/0005-clients-sincronos-com-crt.md))                                                              |

Os módulos de cada solução estão no README dela: [`quote-solution`](../quote-solution/docs/README.md#módulos-maven)
e [`provider-solution`](../provider-solution/docs/README.md#funções-lambda-e-papéis); a visão conjunta fica em
[Desenho técnico § Módulos](plataforma-de-cotacao.md#1-módulos).

## Configuração

Todas as Lambdas de uma stack recebem as variáveis de `provider.environment` do `serverless.yml`, e cada
função acrescenta as próprias. A aplicação Spring as lê pelo `application.yml` de cada módulo, e a Quarkus,
pelo `application.properties`.

Região, conta e endpoint vêm das cadeias padrão do AWS SDK, então o mesmo código roda no Floci e na AWS.
O `params.default` traz os valores da AWS, e as variáveis de endpoint (`AWS_ENDPOINT_URL` e, na provider,
`AWS_ENDPOINT_URL_S3`) só existem quando o param `awsEndpointUrl` não é vazio, pela condição
`HasAwsEndpointUrl`: o SDK trataria um valor vazio como endpoint. Os valores de cada stage estão em
[Desenho técnico § Parâmetros por stage](plataforma-de-cotacao.md#12-parâmetros-por-stage), e as variáveis de
cada solução, na seção Configuração do README dela.

Nas soluções Spring, os functions de um mesmo zip se distinguem por `SPRING_CLOUD_FUNCTION_DEFINITION`, e
o `FunctionInvoker` encontra a aplicação por `MAIN_CLASS`. Nas Quarkus, o handler de cada Lambda vem de
`QUARKUS_LAMBDA_HANDLER`, que bate com o `@Named` do handler. Os Dev Services do Quarkus ficam desligados
(`quarkus.devservices.enabled=false`): o ambiente local é o Floci, nos testes e nas Lambdas.

## Empacotamento

O `mvn package` gera um zip por módulo no formato que o runtime `java21` da Lambda carrega: classes
na raiz e dependências em `lib/`. Não há jar executável do Spring Boot. No Spring, o `spring-parent`
monta o `<artifactId>-lambda.zip` com o `maven-assembly-plugin`.

```text
provider-api-lambda.zip / provider-functions-lambda.zip
├── com/mmda/serverless/provider/...  classes compiladas
├── application.yml
└── lib/                              dependências de runtime
```

No Quarkus, as extensões de Lambda geram o `function.zip` no mesmo formato, com as classes que o
Quarkus produz no build. O nome é fixo na extensão, e o Serverless envia os artefatos ao bucket de
deploy pelo nome do arquivo: com dois módulos Quarkus na mesma stack, um zip sobrescreveria o outro.
Por isso o `quarkus-parent` monta, a partir do `function.zip`, um zip novo chamado
`<artifactId>-lambda.zip`, já com a troca do `aws-crt` descrita abaixo, e o `serverless.yml` aponta
para ele.

```text
quote-api-lambda.zip / quote-functions-lambda.zip
├── com/mmda/serverless/quote/...     classes compiladas
├── io/quarkus/runtime/generated/...  classes geradas no build do Quarkus
├── application.properties
└── lib/                              dependências de runtime
```

Nos dois frameworks, o `lib/` leva o `aws-crt` só com os nativos de Linux, nos jars `linux-x86_64` e
`linux-aarch_64`, no lugar do jar completo com os nativos de todas as plataformas. Os testes
continuam com o jar completo ([ADR 0012](decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md)).

## Ambiente local

```plantuml
@startuml ambiente-local
!include diagramas/tema-plataforma.iuml
left to right direction
hide stereotype
skinparam componentStyle rectangle
title Ambiente local

actor "Desenvolvedor" as dev
component "Serverless Framework v4 e serverless-localstack" as sls <<entrada>>
component "$I_FLOCI Floci" as floci <<infra>>
component "Contêineres das Lambdas - imagem java 21" as lambdas

dev --> sls : pnpm deploy:local
sls --> floci : CloudFormation em localhost:4566, três stacks na ordem do README
floci --> lambdas : socket do Docker
lambdas --> floci : AWS_ENDPOINT_URL floci:4566

legend right
    <b>Leitura</b>
    <back:$COR_ATOR>    </back> Ator
    <back:$COR_ENTRADA>    </back> Ferramenta de implantação
    <back:$COR_APLICACAO>    </back> Lambda
    <back:$COR_INFRA>    </back> Emulador da AWS
endlegend
@enduml
```

- O Floci cria um container por Lambda a partir da imagem oficial `public.ecr.aws/lambda/java:21` e
  injeta `AWS_ENDPOINT_URL=http://floci:4566` por causa de `FLOCI_HOSTNAME=floci` no `compose.yaml`.
- O `pnpm deploy:local` empacota os módulos e implanta as stacks na ordem descrita em
  [Implantação no Floci](../README.md#implantação-no-floci).
- Decisão sobre o emulador e os plugins: [ADR 0001](decisoes/0001-serverless-framework-e-floci.md).

## Permissões

Cada solução declara no `serverless.yml` as roles das Lambdas com o mínimo que cada papel precisa: a
`quote-solution` tem uma role por função, e a `provider-solution` usa a role de `provider.iam` para os
workers e roles próprias para a API de callbacks e os simuladores. Os ARNs das políticas são montados
pelos nomes de `custom.names` (por exemplo, o `custom.stateMachineArn` da quote): um `Ref` à máquina de
estado nas roles das Lambdas que ela invoca criaria dependência circular. O Floci não aplica essas
políticas por padrão; a verificação na AWS está em
[Implantação e verificação no stage `dev`](implantacao-aws-dev.md). O detalhe de cada solução está em
[`quote-solution` § Segurança](../quote-solution/docs/README.md#segurança) e
[`provider-solution` § Segurança e IAM](../provider-solution/docs/README.md#segurança-e-iam).
