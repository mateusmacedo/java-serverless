# 0012. Pacote da Lambda com os jars Linux do aws-crt

- Status: aceita
- Data: 2026-11-11
- Substitui: o item do jar completo do `aws-crt` no [ADR 0005](0005-clients-sincronos-com-crt.md)

## Contexto

O [ADR 0005](0005-clients-sincronos-com-crt.md) manteve o jar completo do `aws-crt` no pacote de cada
Lambda para funcionar em x86_64 e arm64 sem trocar dependência. Esse jar tem cerca de 22 MB, quase
todos de bibliotecas nativas de plataformas que a Lambda não usa (macOS, Windows, musl, ARM de 32
bits). A AWS publica jars por plataforma, de 2,7 MB, com as mesmas classes Java e a biblioteca nativa
de uma plataforma só.

Duas restrições pesam:

- O Floci roda o container da Lambda na arquitetura do host quando `honourArchitectures` está
  desligado (`ContainerLauncher.java`, no código do Floci). Num Mac com Apple Silicon, isso é arm64.
- Os testes rodam na JVM da máquina de quem desenvolve, que pode ser macOS, e precisam do nativo dela.

## Decisão

- O pacote da Lambda leva `aws-crt` com os classificadores `linux-x86_64` e `linux-aarch_64`, no lugar
  do jar completo. O classpath de compilação e de teste continua com o jar completo.
- No Spring, o `spring-parent` exclui o `aws-crt` do `copy-dependencies` e copia os dois jars Linux
  para `lib/`.
- No Quarkus, o `quarkus-parent` copia os dois jars Linux e monta o `<artifactId>-lambda.zip` a partir
  do `function.zip`, trocando o jar completo pelos dois. A troca só acontece se o pacote tiver o
  `aws-crt`.
- Os dois parents fixam a versão do `aws-crt` igual ao `awscrt.version` do AWS SDK que cada um usa
  (0.50.0 no Spring e 0.48.4 no Quarkus), para o jar completo dos testes e os jars Linux do pacote
  serem da mesma versão.

## Consequências

- Os pacotes encolheram cerca de 17 MB cada: `api` de 56 para 38 MB, `functions` de 61 para 43 MB,
  `receipt-api` de 49 para 32 MB e `receipt-functions` de 38 para 21 MB.
- Os pacotes continuam funcionando em x86_64 e arm64. O E2E das duas soluções passou no Floci em
  x86_64; o caminho arm64 não foi exercitado nesta máquina.
- As classes Java do CRT aparecem duas vezes no pacote, uma em cada jar Linux. São idênticas, então
  não há conflito.
- Ao subir o AWS SDK do Spring ou o Quarkus, a propriedade `aws-crt.version` do parent precisa
  acompanhar o `awscrt.version` do `aws-sdk-java-pom` correspondente. Com versões diferentes, os
  testes usariam um CRT e a Lambda outro.

## Alternativas consideradas

- Só o jar `linux-x86_64`: o menor pacote, mas quebra no Floci em hosts arm64 e em Lambdas arm64.
- Ligar `honourArchitectures` no Floci e levar só `linux-x86_64`: o container rodaria emulado em
  hosts arm64, com cold start mais lento.
- Recortar o jar completo, mantendo só os nativos Linux: dispensa fixar a versão, mas reempacota um
  jar de terceiros.
