/** Resultado de uma rodada: os segundos até a próxima, ou `null` quando o recurso terminou e o polling para. */
export type PollOutcome = number | null;

export interface PollerOptions {
  /** Uma rodada de leitura; lança em falha, e o poller tenta de novo mais tarde. */
  readonly tick: () => Promise<PollOutcome>;
  /** Intervalo depois de uma falha, e teto do recuo quando as falhas se repetem. */
  readonly retrySeconds: number;
  readonly maxRetrySeconds?: number;
  readonly document: Document;
  readonly setTimer?: (callback: () => void, ms: number) => unknown;
  readonly clearTimer?: (handle: unknown) => void;
}

/**
 * Polling do contrato compartilhado: uma rodada por vez, sem requisições sobrepostas; o intervalo vem de cada rodada
 * (o `retryAfterSeconds` da versão); com a aba oculta, nada é agendado, e ao voltar há uma rodada imediata; a rodada
 * que devolve `null` encerra o polling.
 */
export class Poller {
  readonly #options: PollerOptions;
  #running = false;
  #finished = false;
  #failures = 0;
  #timer: unknown = null;
  #inFlight: Promise<void> | null = null;
  readonly #onVisibility = () => {
    if (this.#options.document.hidden) {
      this.#cancelTimer();
    } else if (this.#running && !this.#finished) {
      void this.#round();
    }
  };

  constructor(options: PollerOptions) {
    this.#options = options;
  }

  /** Indica se o polling terminou porque o recurso chegou ao fim. */
  get finished(): boolean {
    return this.#finished;
  }

  /** Começa com uma rodada imediata. */
  start(): Promise<void> {
    if (!this.#running) {
      this.#running = true;
      this.#options.document.addEventListener('visibilitychange', this.#onVisibility);
    }
    return this.#round();
  }

  /** Para de vez, como ao sair da tela. Uma rodada em andamento termina, mas não agenda outra. */
  stop(): void {
    this.#running = false;
    this.#cancelTimer();
    this.#options.document.removeEventListener('visibilitychange', this.#onVisibility);
  }

  /**
   * Rodada pedida pela tela, como o "Atualizar": espera a rodada em andamento em vez de abrir outra. Depois do fim do
   * polling, lê uma vez sem voltar a agendar.
   */
  refresh(): Promise<void> {
    return this.#inFlight ?? this.#round();
  }

  #round(): Promise<void> {
    if (this.#inFlight !== null) {
      return this.#inFlight;
    }
    this.#cancelTimer();
    this.#inFlight = this.#options
      .tick()
      .then(
        (next) => {
          this.#failures = 0;
          if (next === null) {
            this.#finished = true;
          } else {
            this.#finished = false;
            this.#schedule(next);
          }
        },
        () => {
          this.#failures++;
          this.#schedule(this.#backoff());
        },
      )
      .finally(() => {
        this.#inFlight = null;
      });
    return this.#inFlight;
  }

  #schedule(seconds: number): void {
    this.#cancelTimer();
    if (!this.#running || this.#finished || this.#options.document.hidden) {
      return;
    }
    const setTimer = this.#options.setTimer ?? ((callback, ms) => setTimeout(callback, ms));
    this.#timer = setTimer(() => {
      this.#timer = null;
      void this.#round();
    }, seconds * 1000);
  }

  #backoff(): number {
    const max = this.#options.maxRetrySeconds ?? 30;
    return Math.min(this.#options.retrySeconds * 2 ** (this.#failures - 1), max);
  }

  #cancelTimer(): void {
    if (this.#timer !== null) {
      const clearTimer = this.#options.clearTimer ?? ((handle) => clearTimeout(handle as number));
      clearTimer(this.#timer);
      this.#timer = null;
    }
  }
}
