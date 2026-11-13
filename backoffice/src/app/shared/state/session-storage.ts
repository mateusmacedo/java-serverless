import { DOCUMENT, inject, Injectable, InjectionToken } from '@angular/core';

import type { AttemptStore, MutationAttempt } from './idempotent-mutation';

/** Prefixo de tudo o que o painel guarda no `sessionStorage`; a saída apaga tudo o que começa por ele. */
export const STORAGE_PREFIX = 'backoffice.';

const PARTITION_PREFIX = `${STORAGE_PREFIX}p.`;

/** `sessionStorage` da aba, ou `null` quando o navegador o bloqueia. Os testes trocam pelo próprio armazenamento. */
export const BROWSER_STORAGE = new InjectionToken<Storage | null>('BROWSER_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return inject(DOCUMENT).defaultView?.sessionStorage ?? null;
    } catch {
      return null;
    }
  },
});

/**
 * Armazenamento da aba particionado por usuário e organização: o que um usuário guarda não aparece para outro na
 * mesma aba. Guarda só metadados mínimos, como a chave e o corpo de uma tentativa de envio; nunca respostas da API.
 */
@Injectable({ providedIn: 'root' })
export class SessionScopedStorage {
  readonly #storage = inject(BROWSER_STORAGE);
  #partition: string | null = null;

  /** Passa a ler e gravar na partição do usuário e apaga as partições de outros usuários desta aba. */
  usePartition(userId: string, organizationId: string): void {
    this.#partition = `${encodeURIComponent(organizationId)}.${encodeURIComponent(userId)}`;
    const own = `${PARTITION_PREFIX}${this.#partition}.`;
    for (const key of this.#keys()) {
      if (key.startsWith(PARTITION_PREFIX) && !key.startsWith(own)) {
        this.#remove(key);
      }
    }
  }

  read<T>(key: string): T | null {
    const fullKey = this.#fullKey(key);
    if (!fullKey) {
      return null;
    }
    try {
      const raw = this.#storage?.getItem(fullKey);
      return raw ? (JSON.parse(raw) as T) : null;
    } catch {
      return null;
    }
  }

  write(key: string, value: unknown): void {
    const fullKey = this.#fullKey(key);
    if (!fullKey) {
      return;
    }
    try {
      this.#storage?.setItem(fullKey, JSON.stringify(value));
    } catch {
      // NOTE: sem armazenamento (aba privada, cota cheia), a recuperação vale só enquanto a tela estiver aberta.
    }
  }

  remove(key: string): void {
    const fullKey = this.#fullKey(key);
    if (fullKey) {
      this.#remove(fullKey);
    }
  }

  /** Apaga tudo o que o painel guardou nesta aba, de todos os usuários. */
  clearAll(): void {
    this.#partition = null;
    for (const key of this.#keys()) {
      if (key.startsWith(STORAGE_PREFIX)) {
        this.#remove(key);
      }
    }
  }

  /** Tentativa de envio da intenção `scope` (como `create-quote`), na partição do usuário. */
  attemptStore<TBody>(scope: string): AttemptStore<TBody> {
    const key = `attempt.${scope}`;
    return {
      load: () => this.read<MutationAttempt<TBody>>(key),
      save: (attempt) => this.write(key, attempt),
      clear: () => this.remove(key),
    };
  }

  #fullKey(key: string): string | null {
    return this.#partition ? `${PARTITION_PREFIX}${this.#partition}.${key}` : null;
  }

  #keys(): string[] {
    const storage = this.#storage;
    if (!storage) {
      return [];
    }
    try {
      return Array.from({ length: storage.length }, (_, index) => storage.key(index)).filter(
        (key): key is string => key !== null,
      );
    } catch {
      return [];
    }
  }

  #remove(key: string): void {
    try {
      this.#storage?.removeItem(key);
    } catch {
      // NOTE: sem armazenamento, não há o que apagar.
    }
  }
}
