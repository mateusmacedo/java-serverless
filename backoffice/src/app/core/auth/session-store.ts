import {
  computed,
  type EnvironmentProviders,
  inject,
  Injectable,
  makeEnvironmentProviders,
  provideAppInitializer,
  signal,
} from '@angular/core';

import { getMe, type Me, type Permission } from '../../shared/api';
import { SessionScopedStorage } from '../../shared/state/session-storage';
import { type ApiError, apiData, toApiError } from '../../shared/util/api-error';
import { AuthSession } from './auth-session';

/**
 * `idle` antes do login; `loading` durante a chamada; `ready` com a sessão; `error` com o erro da chamada; `expired`
 * quando o servidor recusou o token mesmo depois da renovação.
 */
export type SessionStatus = 'idle' | 'loading' | 'ready' | 'error' | 'expired';

interface SessionState {
  readonly status: SessionStatus;
  readonly me?: Me;
  readonly error?: ApiError;
}

/**
 * Quem está usando o painel, como o servidor responde em `GET /v1/me`: usuário, organização e permissões. A
 * navegação e os botões seguem estas permissões, mas quem autoriza cada recurso é o servidor.
 */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  readonly #storage = inject(SessionScopedStorage);
  readonly #state = signal<SessionState>({ status: 'idle' });
  #loading: Promise<void> | null = null;

  readonly status = computed(() => this.#state().status);
  readonly error = computed(() => this.#state().error ?? null);
  readonly me = computed(() => this.#state().me ?? null);
  readonly permissions = computed<ReadonlySet<Permission>>(
    () => new Set(this.#state().me?.permissions ?? []),
  );

  /** Carrega a sessão se ela ainda não foi pedida; com uma carga em andamento, espera a mesma. */
  ensureLoaded(): Promise<void> {
    return this.#state().status === 'idle' ? this.load() : (this.#loading ?? Promise.resolve());
  }

  /** Carrega `GET /v1/me`. Não rejeita: a falha vira o estado `error` ou, no 401, `expired`. */
  load(): Promise<void> {
    this.#loading ??= this.#fetch().finally(() => (this.#loading = null));
    return this.#loading;
  }

  can(permission: Permission | null | undefined): boolean {
    return !permission || this.permissions().has(permission);
  }

  async #fetch(): Promise<void> {
    this.#state.update((state) => ({ ...state, status: 'loading', error: undefined }));
    try {
      const me = await apiData(getMe());
      this.#storage.usePartition(me.userId, me.organizationId);
      this.#state.set({ status: 'ready', me });
    } catch (error_) {
      const error = toApiError(error_);
      this.#state.set({ status: error.status === 401 ? 'expired' : 'error', error });
    }
  }
}

/** Carrega a sessão no início do app, quando há login; vai depois de `provideQuoteApi()`. */
export function provideSession(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideAppInitializer(async () => {
      const auth = inject(AuthSession);
      const session = inject(SessionStore);
      if (auth.isAuthenticated()) {
        await session.load();
      }
    }),
  ]);
}
