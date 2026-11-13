# Plataforma

O que as soluções compartilham, na pasta `platform/`: o barramento de eventos da plataforma de cotação
(`platform/events`), um parent Maven por framework (`spring-parent` e `quarkus-parent`) e a biblioteca
`spring-aws`, que cria os clients do AWS SDK nas soluções Spring. As decisões são o
[ADR 0013](../../docs/decisoes/0013-barramento-da-plataforma.md) (barramento), o
[ADR 0010](../../docs/decisoes/0010-raiz-neutra-e-parents-por-framework.md) (parents) e o
[ADR 0005](../../docs/decisoes/0005-clients-sincronos-com-crt.md) (clients síncronos com CRT). As soluções
estão documentadas na própria pasta: [`sample-solution`](../../sample-solution/docs/README.md),
[`receipt-solution`](../../receipt-solution/docs/README.md) e, para a cotação, o
[desenho técnico](../../docs/plataforma-de-cotacao.md).

## Sumário

- [Propósito e responsabilidades](#propósito-e-responsabilidades)
- [Barramento de eventos](#barramento-de-eventos)
- [Parents Maven](#parents-maven)
- [spring-aws](#spring-aws)
- [Segurança e IAM](#segurança-e-iam)
- [Observabilidade e alarmes](#observabilidade-e-alarmes)
- [Configuração](#configuração)
- [Ordem de implantação](#ordem-de-implantação)
- [Rodar localmente no Floci](#rodar-localmente-no-floci)
- [Implantar na AWS](#implantar-na-aws)
- [Testes](#testes)
- [Diagramas](#diagramas)
- [Divergências conhecidas](#divergências-conhecidas)

## Propósito e responsabilidades

| Peça | Tipo | Responsabilidade | Quem usa |
|---|---|---|---|
| [`platform/events`](../events/serverless.yml) | Stack Serverless (serviço `platform`), sem funções | Barramento `platform-<stage>-events`, onde a `quote-solution` e a `provider-solution` publicam e declaram as regras | `quote-solution`, `provider-solution` |
| [`platform/spring-parent`](../spring-parent/pom.xml) | `pom` | BOMs do Spring Boot (importado primeiro), Spring Cloud e AWS SDK; exclusão de `apache-client` e `netty-nio-client`; zip da Lambda | `sample-solution`, `provider-solution`, `spring-aws` |
| [`platform/quarkus-parent`](../quarkus-parent/pom.xml) | `pom` | BOMs do Quarkus (linha LTS) e do Quarkus Amazon Services; `quarkus-maven-plugin`; `<artifactId>-lambda.zip` | `receipt-solution`, `quote-solution` |
| [`platform/spring-aws`](../spring-aws/pom.xml) | `jar` | Autoconfiguração dos clients síncronos do SDK v2 com `AwsCrtHttpClient` | Módulos Spring da `sample-solution` e da `provider-solution` |

O `pom.xml` da raiz (`serverless-parent`) é neutro: Java 21, plugins Maven e `testcontainers-floci-aws`. As
versões de cada peça estão em [README § Tecnologias](../../README.md#tecnologias).

## Barramento de eventos

`platform/events/serverless.yml` cria um único recurso, `PlatformEventBus` (`AWS::Events::EventBus`), com o nome
`platform-<stage>-events`, e exporta as saídas `PlatformEventBusName` e `PlatformEventBusArn`. Nenhuma solução
lê essas saídas: todas chegam ao barramento pelo nome, por convenção, sem referência entre stacks.

O barramento não tem regras próprias. Cada solução consumidora declara, no próprio `serverless.yml`, as regras
com filas SQS como alvo; cada fila alvo tem DLQ e uma política que só aceita a regra dela como `aws:SourceArn`:

| Regra | Declarada em | `source` | `detail-type` | Alvo |
|---|---|---|---|---|
| `EnrichmentRequestedRule` | `provider-solution` | `mmda.quote` | `EnrichmentRequested` | `provider-solution-<stage>-enrichment-requests` (`enrichmentWorker`) |
| `InsurerQuoteRequestedRule` | `provider-solution` | `mmda.quote` | `InsurerQuoteRequested` | `provider-solution-<stage>-insurer-requests` (`insurerWorker`) |
| `QueryReconcileRequestedRule` | `provider-solution` | `mmda.quote` | `QueryReconcileRequested` | `provider-solution-<stage>-reconcile-requests` (`reconcileWorker`) |
| `ProviderResultsRule` | `quote-solution` | `mmda.provider` | `EnrichmentResolved`, `InsurerResultRecorded` | `quote-solution-<stage>-results` (`resume`) |
| `ProviderIssuesRule` | `quote-solution` | `mmda.provider` | `QueryReconciled`, `OperationIssueRaised`, `OperationIssueUpdated` | `quote-solution-<stage>-issues` (`issueProjector`) |

As duas soluções publicam pela outbox na tabela: a `outboxRelay` lê o DynamoDB Streams e chama o `PutEvents`, e
o `reconciler` republica o que ficou pendente
([ADR 0015](../../docs/decisoes/0015-modelo-dynamodb-e-outbox-da-cotacao.md),
[desenho técnico § Relay de outbox](../../docs/plataforma-de-cotacao.md#relay-de-outbox-as-duas-soluções)).
`QuoteAccepted`, `QuoteCompleted` e `OfferSelected` são publicados pela quote sem regra consumidora no
repositório. Os campos de cada evento estão no [desenho técnico § 3](../../docs/plataforma-de-cotacao.md#3-eventos)
e nos AsyncAPI da [quote](../../contracts/quote-solution/asyncapi.yaml) e da
[provider](../../contracts/provider-solution/asyncapi.yaml).

A `sample-solution` e a `receipt-solution` seguem o padrão anterior, com barramento por solução e a regra da
consumidora no barramento da produtora ([ADR 0011](../../docs/decisoes/0011-integracao-entre-solucoes-por-eventbridge.md));
migrá-las é evolução separada.

## Parents Maven

| Parent | Herdam dele | O que define |
|---|---|---|
| `spring-parent` | `spring-aws`, `sample-solution`, `provider-solution` | BOM do Spring Boot antes dos demais, para manter a precedência que tinha como parent; Spring Cloud e AWS SDK; exclusão de `apache-client` e `netty-nio-client` de cada service do SDK; versões de `aws-serverless-java-container`, `openapi-request-validator` e `aws-crt`; execuções `copy-lambda-dependencies`, `copy-lambda-crt-natives` e `lambda-zip` (descritor inline do `maven-assembly-plugin`), ativadas no módulo que declara o `maven-dependency-plugin` e o `maven-assembly-plugin` |
| `quarkus-parent` | `receipt-solution`, `quote-solution` | BOMs do Quarkus e do Quarkus Amazon Services; `quarkus-maven-plugin` (`build`, `generate-code`, `generate-code-tests`); versões fora do BOM (`aws-crt`, AssertJ, `openapi-request-validator-restassured`); cópia de `target/function.zip` para `<artifactId>-lambda.zip` (`maven-antrun-plugin`), com o `aws-crt` só nos jars Linux |

O formato dos zips e a troca do `aws-crt` completo pelos jars `linux-x86_64` e `linux-aarch_64` estão em
[Arquitetura § Empacotamento](../../docs/arquitetura.md#empacotamento) e no
[ADR 0012](../../docs/decisoes/0012-aws-crt-com-nativos-linux-no-pacote.md). Uma solução nova escolhe o parent do
framework e ganha o próprio `serverless.yml`.

## spring-aws

[`AwsClientsAutoConfiguration`](../spring-aws/src/main/java/com/mmda/serverless/platform/spring/aws/AwsClientsAutoConfiguration.java)
é registrada em `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Cada client
vem de uma configuração interna com `@ConditionalOnClass` e `@ConditionalOnMissingBean`, e recebe o próprio
`AwsCrtHttpClient` pelo builder. Os services do SDK são dependências opcionais do `spring-aws`: o módulo da
solução declara só os que usa, e só esses clients sobem.

| Client | Configuração extra | Módulos que declaram o SDK |
|---|---|---|
| `DynamoDbClient`, `SqsClient`, `SnsClient`, `EventBridgeClient`, `SecretsManagerClient`, `SsmClient`, `SfnClient` | Nenhuma | Conforme o uso de cada módulo |
| `S3Client` | `forcePathStyle` de `mmda.aws.s3.path-style-access` (padrão `false`) | `sample-solution/api`, `sample-solution/functions`, `provider-solution/api` e `provider-solution/functions` |
| `SchedulerClient` | Nenhuma | Nenhum módulo de solução hoje |

Região, credenciais e endpoint vêm das cadeias padrão do SDK (inclusive `AWS_ENDPOINT_URL`), então o mesmo
código roda no Floci e na AWS. Para trocar um client, a aplicação declara o próprio bean do mesmo tipo. No
Quarkus não há equivalente: os clients vêm das extensões do Quarkus Amazon Services com
`sync-client.type=aws-crt`.

## Segurança e IAM

- O barramento não declara política de recurso; o acesso vem do IAM de quem publica, na mesma conta. A quote e
  a provider dão `events:PutEvents` no ARN `arn:aws:events:<região>:<conta>:event-bus/platform-<stage>-events`,
  montado por nome, aos papéis que publicam.
- As filas alvo aceitam `sqs:SendMessage` só de `events.amazonaws.com` com `aws:SourceArn` igual à regra que as
  alimenta.
- Uma regra com filtro errado entregaria eventos a quem não deveria; por isso cada evento produzido tem teste de
  contrato (ADR 0013).
- O `spring-aws` não guarda credenciais: usa a cadeia padrão do SDK.

## Observabilidade e alarmes

A stack `platform` não declara alarmes nem arquivamento de eventos. Os alarmes das DLQs das filas alvo ficam nas
stacks consumidoras ([desenho técnico § 9](../../docs/plataforma-de-cotacao.md#9-falhas-retries-e-orçamento-de-tempo)).

## Configuração

A stack não tem `params` nem variáveis. O stage vem de `--stage` (padrão `local`), a região é fixa em
`us-east-1` e o `serverless-localstack` só fica ativo no stage `local`, apontando para `localhost:4566`.

## Ordem de implantação

| Stack | Depende de | Motivo |
|---|---|---|
| `platform` (`platform/events`) | — | Cria o barramento usado pelas regras da quote e da provider |
| `sample-solution` | — | Cria o barramento onde fica a regra da receipt |
| `receipt-solution` | `sample-solution` | Regra no barramento da sample (ADR 0011) |
| `provider-solution` | `platform` | Regras e `PutEvents` no barramento da plataforma (ADR 0013) |
| `quote-solution` | `platform` | Idem |

Na remoção, a ordem se inverte: quem tem regra num barramento sai antes da stack dona dele. O `deploy:local`
roda as cinco em sequência (plataforma, sample, receipt, provider e quote); o diagrama
[`atividade-ordem-de-implantacao.puml`](diagramas/atividade-ordem-de-implantacao.puml) mostra a implantação e a
remoção.

## Rodar localmente no Floci

```bash
pnpm deploy:platform
```

O script entra em `platform/events` e roda `sls deploy --stage local` com as chaves de teste. A ordem completa
está em [README § Implantação no Floci](../../README.md#implantação-no-floci).

## Implantar na AWS

O barramento é a primeira stack do [guia do stage `dev`](../../docs/implantacao-aws-dev.md#3-implantação), antes
da provider e da quote. A `sample-solution` e a `receipt-solution` não estão preparadas para a AWS.

## Testes

O `AwsClientsAutoConfigurationTest` cobre a autoconfiguração do `spring-aws`
([Testes § Unitários](../../docs/testes.md#unitários)). O barramento não tem teste próprio: os eventos e as
regras são conferidos nas soluções (por exemplo, o `EventContractTest` e o `ServerlessConfigurationTest` da
provider), e o `QuoteFlowE2E` passa por ele ponta a ponta ([Testes § Ponta a ponta](../../docs/testes.md#ponta-a-ponta)).

## Diagramas

Arquivos PlantUML em [`diagramas/`](diagramas), com o tema único
[`tema-plataforma.iuml`](../../docs/diagramas/tema-plataforma.iuml). Para renderizar, na raiz:

```bash
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w plantuml/plantuml:latest \
  -tsvg -o /w/build/diagramas/platform 'platform/docs/diagramas/*.puml'
```

| Diagrama | O que mostra e como ler |
|---|---|
| [`c4-conteiner.puml`](diagramas/c4-conteiner.puml) | Barramento, parents e `spring-aws` com as soluções ao redor. Tracejado roxo é evento no barramento; pontilhado verde é herança ou dependência Maven |
| [`implantacao-floci.puml`](diagramas/implantacao-floci.puml) | As cinco stacks no Floci na ordem do `deploy:local` (setas azuis) e as regras que ligam as stacks pelo nome do barramento |
| [`implantacao-aws.puml`](diagramas/implantacao-aws.puml) | Stage `dev`: plataforma, provider e quote, nessa ordem; sample e receipt em vermelho, fora do guia |
| [`atividade-ordem-de-implantacao.puml`](diagramas/atividade-ordem-de-implantacao.puml) | Ordem de implantação por grupo de barramento e a remoção na ordem inversa |
| [`pacotes-maven.puml`](diagramas/pacotes-maven.puml) | Raiz neutra, parents, `spring-aws` e soluções; seta cheia é herança, tracejada é dependência |
| [`classes-spring-aws.puml`](diagramas/classes-spring-aws.puml) | Autoconfiguração, as nove configurações internas e as propriedades `mmda.aws` |

## Divergências conhecidas

1. **Ordem entre provider e quote.** O [guia do `dev`](../../docs/implantacao-aws-dev.md#1-visão-geral) chama de
   obrigatória a ordem plataforma, provider e quote. O ADR 0013 diz que, depois do barramento, as soluções sobem
   em qualquer ordem, e os `serverless.yml` não têm referência entre a provider e a quote; o que importa é o
   barramento antes das duas.
2. **Caminho do tema.** O [`docs/README.md`](../../docs/README.md#diagramas) e o cabeçalho do tema indicam
   `!include ../../docs/diagramas/tema-plataforma.iuml` para `<solução>/docs/diagramas/`, caminho que não
   renderiza. O certo é `../../../docs/diagramas/tema-plataforma.iuml`, usado aqui.
3. **Comando de renderização.** O comando único de [`docs/README.md`](../../docs/README.md#diagramas) grava tudo
   em `build/diagramas/`, e diagramas de mesmo nome em pastas diferentes se sobrescrevem.
4. **`SchedulerClient` sem uso.** O `spring-aws` oferece o client do EventBridge Scheduler, mas nenhum módulo de
   solução declara o SDK `scheduler`; o agendamento do `reprocess` da sample é criado pelo Serverless, sem
   chamada de SDK.
