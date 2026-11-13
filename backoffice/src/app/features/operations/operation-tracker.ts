import { DestroyRef, DOCUMENT, inject, Injectable, signal } from '@angular/core';

import { APP_CONFIG } from '../../core/config/app-config';
import { getOperation, type Operation } from '../../shared/api';
import { Poller } from '../../shared/state/poller';
import { SessionScopedStorage } from '../../shared/state/session-storage';
import { type ApiError, apiData, toApiError } from '../../shared/util/api-error';
import { isOperationFinished } from './operations-presentation';

const STORAGE_KEY = 'operations.tracked';

/** Reconciliação acompanhada na tela: a situação mais recente e o erro da última leitura. */
export interface TrackedOperation {
  readonly operationId: string;
  readonly queryId: string;
  readonly operation: Operation | null;
  readonly error: ApiError | null;
}

/**
 * Acompanhamento das reconciliações pedidas nesta aba, pelo `GET /v1/operations/{id}`, até `SUCCEEDED` ou `FAILED`.
 * Só lê: nunca repete o comando. Os ids ficam na sessão da aba, para o acompanhamento continuar ao voltar à tela.
 */
@Injectable()
export class OperationTracker {
  readonly #config = inject(APP_CONFIG);
  readonly #document = inject(DOCUMENT);
  readonly #storage = inject(SessionScopedStorage);
  readonly #pollers = new Map<string, Poller>();
  readonly #tracked = signal<readonly TrackedOperation[]>([]);

  readonly tracked = this.#tracked.asReadonly();

  constructor() {
    const saved = this.#storage.read<{ operationId: string; queryId: string }[]>(STORAGE_KEY) ?? [];
    for (const { operationId, queryId } of saved) {
      this.track(operationId, queryId);
    }
    inject(DestroyRef).onDestroy(() => this.#pollers.forEach((poller) => poller.stop()));
  }

  track(operationId: string, queryId: string): void {
    if (this.#pollers.has(operationId)) {
      return;
    }
    this.#tracked.update((items) => [
      { operationId, queryId, operation: null, error: null },
      ...items,
    ]);
    this.#save();
    const poller = new Poller({
      tick: async () => {
        try {
          const operation = await apiData(getOperation({ path: { operationId } }));
          this.#update(operationId, { operation, error: null });
          return isOperationFinished(operation.status) ? null : this.#config.pollIntervalSeconds;
        } catch (thrown) {
          const error = toApiError(thrown);
          this.#update(operationId, { error });
          if (error.status === 403 || error.status === 404) {
            return null;
          }
          throw error;
        }
      },
      retrySeconds: this.#config.pollIntervalSeconds,
      document: this.#document,
    });
    this.#pollers.set(operationId, poller);
    void poller.start();
  }

  /** Lê a situação de novo, como no "Atualizar" depois de uma falha de leitura. */
  refresh(operationId: string): Promise<void> {
    return this.#pollers.get(operationId)?.refresh() ?? Promise.resolve();
  }

  /** Tira da tela uma reconciliação já concluída. */
  dismiss(operationId: string): void {
    this.#pollers.get(operationId)?.stop();
    this.#pollers.delete(operationId);
    this.#tracked.update((items) => items.filter((item) => item.operationId !== operationId));
    this.#save();
  }

  #update(operationId: string, changes: Partial<TrackedOperation>): void {
    this.#tracked.update((items) =>
      items.map((item) => (item.operationId === operationId ? { ...item, ...changes } : item)),
    );
  }

  #save(): void {
    this.#storage.write(
      STORAGE_KEY,
      this.#tracked().map(({ operationId, queryId }) => ({ operationId, queryId })),
    );
  }
}
