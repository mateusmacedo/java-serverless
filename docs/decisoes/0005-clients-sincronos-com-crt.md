# 0005. Clients síncronos do AWS SDK com CRT e SDKs opcionais

- Status: aceita; o item do jar completo do `aws-crt` foi substituído pelo [0012](0012-aws-crt-com-nativos-linux-no-pacote.md)
- Data: 2026-11-10

## Contexto

No modelo convencional ([ADR 0002](0002-spring-mvc-e-spring-cloud-function.md)), os handlers são
imperativos. Na versão anterior, cada service do SDK trazia por padrão dois clientes HTTP (Apache e
Netty), e o módulo `shared` levava os nove SDKs para todas as Lambdas.

## Decisão

- Clients síncronos (`S3Client`, `DynamoDbClient`, `SfnClient` e os demais), criados pela
  autoconfiguração do módulo `shared`.
- Cada client recebe o próprio `AwsCrtHttpClient` pelo builder. A AWS recomenda o CRT para Lambda e
  indica uma instância por client como boa prática
  ([guia do SDK](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-crt.html)).
- `apache-client` e `netty-nio-client` são excluídos de cada service no `dependencyManagement` do POM raiz.
- Os SDKs são dependências opcionais do `shared`: cada módulo declara só os que usa, e cada client sobe
  por `@ConditionalOnClass`.
- Mantido o jar completo do `aws-crt` (23 MB, com bibliotecas nativas de todas as plataformas), em vez
  do jar só `linux-x86_64` (2,7 MB).

## Consequências

- Os handlers chamam o SDK de forma direta, sem `.join()` nem tipos reativos.
- A Lambda `api` carrega só S3 e Step Functions; o módulo `functions` não carrega o SDK do Scheduler.
- Os pacotes ficaram entre 51 e 64 MB, porque o jar completo do CRT ocupa o espaço liberado pelos
  clientes removidos. O deploy funciona porque o Serverless sobe os pacotes via S3.
- Os pacotes funcionam em x86_64 e arm64 sem trocar dependência.

## Alternativas consideradas

- Clients assíncronos (como na versão reativa): exigiriam `.join()` no código imperativo.
- Jar do CRT só `linux-x86_64`: cerca de 20 MB a menos por pacote, amarrado à arquitetura.
- `UrlConnectionHttpClient`: mais leve, mas não é a recomendação da AWS para Lambda.
