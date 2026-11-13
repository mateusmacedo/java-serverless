# 0006. Payload no S3, com só a chave na máquina de estado

- Status: aceita
- Data: 2026-11-10

## Contexto

O Step Functions limita a entrada e a saída de cada estado e da execução a 262.144 bytes (256 KiB)
([cota do Step Functions](https://aws.amazon.com/blogs/compute/introducing-larger-state-payloads-for-aws-step-functions/)).
O conteúdo recebido pela API não tem tamanho controlado no momento da entrada.

## Decisão

A Lambda `api` grava o corpo, sem alteração, em `requests/<requestId>.json` no bucket de payloads e
inicia a execução só com `requestId`, `s3Key` e `attempt`. O `validate` lê o conteúdo do S3.

## Consequências

- O estado da máquina tem tamanho pequeno e previsível, independente do payload.
- O payload original fica guardado e permite reprocessar a requisição a partir da mesma chave.
- Cada passo que precisa do conteúdo faz uma leitura no S3.
- O limite de negócio (4096 bytes, parâmetro SSM) é verificado depois do `202`, no `validate`.

## Alternativas consideradas

- Payload direto na entrada da execução: mais simples, mas falha acima de 256 KiB.
- Validar o tamanho na API antes de iniciar a execução: resposta síncrona de erro, ao custo de ler o
  parâmetro SSM a cada requisição.
