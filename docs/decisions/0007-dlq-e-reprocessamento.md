# 0007. Falhas para a DLQ e reprocessamento com limite de tentativas

- Status: aceita
- Data: 2026-11-10

## Contexto

Uma falha em qualquer passo precisa ficar registrada com o motivo e poder ser tentada de novo, sem
que um payload sempre inválido gere reprocessamento infinito.

## Decisão

- Os estados `Validate`, `Persist` e `Notify` têm um `Catch` para `States.ALL` com
  `ResultPath: $.error`, que leva ao estado `SendToDeadLetterQueue`.
- `SendToDeadLetterQueue` usa a integração `arn:aws:states:::sqs:sendMessage` e envia a entrada do
  estado, com o erro, para a fila `dlq`; em seguida `RequestFailed` encerra a execução como `FAILED`.
- A Lambda `reprocess`, acionada pelo EventBridge Scheduler a cada 5 minutos, lê até 10 mensagens da
  DLQ. Abaixo de `MAX_ATTEMPTS` (padrão 3), inicia nova execução com `attempt + 1` e nome
  `requestId-attempt`; no limite, descarta a mensagem e registra um aviso no log. Em ambos os casos a
  mensagem é apagada da fila.

## Consequências

- A execução falha fica visível tanto no histórico do Step Functions quanto na DLQ.
- Um payload sempre inválido é reiniciado duas vezes e descartado na terceira tentativa.
- Mensagens descartadas não ficam guardadas em outro lugar além do log do `reprocess`.
- Erros transitórios não têm `Retry` no próprio estado; eles esperam o próximo ciclo do reprocessamento.
- O disparo automático do Scheduler foi observado no Floci, a cada 5 minutos, nos logs da função.

## Alternativas consideradas

- `Retry` com backoff em cada estado: trata falhas transitórias, mas não substitui a DLQ para erros
  de negócio.
- Redrive nativo da SQS para outra fila: não reinicia a máquina de estado.
