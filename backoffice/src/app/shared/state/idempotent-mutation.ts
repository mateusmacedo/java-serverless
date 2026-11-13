import { computed, signal } from '@angular/core';

import {
  type ApiError,
  isRetryableRejection,
  isUnknownOutcome,
  toApiError,
} from '../util/api-error';
import { canonicalJson } from '../util/canonical-json';

/**
 * `unknown` é o envio sem resposta, que só o reenvio da mesma chave e do mesmo corpo esclarece; `retryable`, a recusa
 * antes da aceitação durável (`503`, `429`); `rejected`, a recusa definitiva, depois da qual vem outra intenção.
 */
export type MutationState = 'idle' | 'sending' | 'unknown' | 'retryable' | 'rejected' | 'succeeded';

/** Tentativa de uma intenção: a chave e o corpo, reenviados iguais até a resposta chegar. */
export interface MutationAttempt<TBody> {
  readonly key: string;
  readonly body: TBody;
  readonly startedAt: string;
}

/** Onde a tentativa sem resposta sobrevive a uma recarga da página, na sessão da aba. */
export interface AttemptStore<TBody> {
  load(): MutationAttempt<TBody> | null;
  save(attempt: MutationAttempt<TBody>): void;
  clear(): void;
}

export interface IdempotentMutationOptions<TBody, TResult> {
  /** Faz a chamada com a chave e o corpo; lança o erro dela. */
  readonly send: (key: string, body: TBody) => Promise<TResult>;
  readonly store?: AttemptStore<TBody>;
  readonly newKey?: () => string;
  readonly now?: () => Date;
}

/**
 * Envio idempotente de uma intenção, como o contrato pede: uma chave por intenção, reenvio exato depois de um
 * resultado desconhecido e nenhum retry automático. A tentativa é gravada antes da chamada, para a tela recuperar a
 * mesma intenção mesmo depois de recarregada.
 */
export class IdempotentMutation<TBody, TResult> {
  readonly #options: IdempotentMutationOptions<TBody, TResult>;
  readonly #state = signal<MutationState>('idle');
  readonly #error = signal<ApiError | null>(null);
  readonly #result = signal<TResult | null>(null);
  readonly #attempt = signal<MutationAttempt<TBody> | null>(null);

  readonly state = this.#state.asReadonly();
  readonly error = this.#error.asReadonly();
  readonly result = this.#result.asReadonly();
  /** Tentativa em curso ou à espera de esclarecimento; `null` quando não há. */
  readonly attempt = this.#attempt.asReadonly();
  /** O formulário fica travado durante o envio e enquanto o resultado é desconhecido. */
  readonly locked = computed(() => this.#state() === 'sending' || this.#state() === 'unknown');

  constructor(options: IdempotentMutationOptions<TBody, TResult>) {
    this.#options = options;
    const pending = options.store?.load() ?? null;
    if (pending) {
      // INVARIANT: uma tentativa guardada nunca teve resposta conhecida; só o reenvio dela a esclarece.
      this.#attempt.set(pending);
      this.#state.set('unknown');
    }
  }

  /**
   * Envia a intenção. Durante um envio, não faz nada; com resultado desconhecido, também não, porque só `retry` pode
   * reenviar. Depois de uma recusa sem aceitação (`retryable`), o mesmo corpo reusa a chave; outro corpo é outra
   * intenção, com chave nova.
   */
  submit(body: TBody): Promise<TResult | null> {
    const state = this.#state();
    if (state === 'sending' || state === 'unknown') {
      return Promise.resolve(null);
    }
    const previous = this.#attempt();
    const reuse =
      state === 'retryable' &&
      previous !== null &&
      canonicalJson(previous.body) === canonicalJson(body);
    const attempt: MutationAttempt<TBody> = reuse
      ? previous
      : { key: this.#newKey(), body, startedAt: this.#now().toISOString() };
    return this.#send(attempt);
  }

  /** Reenvia exatamente a mesma chave e o mesmo corpo da tentativa sem resposta ou recusada antes da aceitação. */
  retry(): Promise<TResult | null> {
    const attempt = this.#attempt();
    if (!attempt || this.#state() === 'sending') {
      return Promise.resolve(null);
    }
    return this.#send(attempt);
  }

  /** Volta ao início depois de um sucesso ou de uma recusa definitiva; nunca descarta um resultado desconhecido. */
  reset(): void {
    if (this.#state() === 'unknown' || this.#state() === 'sending') {
      return;
    }
    this.#state.set('idle');
    this.#error.set(null);
    this.#result.set(null);
    this.#attempt.set(null);
  }

  async #send(attempt: MutationAttempt<TBody>): Promise<TResult | null> {
    this.#attempt.set(attempt);
    this.#options.store?.save(attempt);
    this.#state.set('sending');
    this.#error.set(null);
    try {
      const result = await this.#options.send(attempt.key, attempt.body);
      this.#options.store?.clear();
      this.#result.set(result);
      this.#state.set('succeeded');
      return result;
    } catch (error_) {
      const error = toApiError(error_);
      this.#error.set(error);
      if (isUnknownOutcome(error)) {
        this.#state.set('unknown');
      } else if (isRetryableRejection(error)) {
        this.#options.store?.clear();
        this.#state.set('retryable');
      } else {
        // NOTE: o IDEMPOTENCY_CONFLICT também encerra a intenção; a próxima tentativa recebe chave nova.
        this.#options.store?.clear();
        this.#attempt.set(null);
        this.#state.set('rejected');
      }
      return null;
    }
  }

  #newKey(): string {
    return this.#options.newKey?.() ?? crypto.randomUUID();
  }

  #now(): Date {
    return this.#options.now?.() ?? new Date();
  }
}
