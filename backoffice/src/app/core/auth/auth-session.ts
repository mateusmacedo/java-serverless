import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';

import {
  BROWSER_STORAGE,
  SessionScopedStorage,
  STORAGE_PREFIX,
} from '../../shared/state/session-storage';
import { BROWSER_LOCATION } from './browser-location';
import { CognitoClient, type CognitoTokens } from './cognito-client';

const TOKENS_KEY = `${STORAGE_PREFIX}auth`;

/** Margem antes do vencimento em que o token já é renovado, para não vencer no meio da chamada. */
const EXPIRY_MARGIN_MS = 60_000;

/**
 * Login do painel no user pool da stack. Os tokens ficam no `sessionStorage` da aba, que some ao fechá-la; o que o
 * usuário pode fazer vem do servidor (`SessionStore`, `GET /v1/me`), e o painel não lê grupos nem claims do token.
 */
@Injectable({ providedIn: 'root' })
export class AuthSession {
  readonly #cognito = inject(CognitoClient);
  readonly #storage = inject(BROWSER_STORAGE);
  readonly #scoped = inject(SessionScopedStorage);
  readonly #location = inject(BROWSER_LOCATION);
  readonly #router = inject(Router);
  readonly #tokens = signal<CognitoTokens | null>(this.#readTokens());
  #refreshing: Promise<string | null> | null = null;

  readonly authenticated = computed(() => this.#tokens() !== null);

  isAuthenticated(): boolean {
    return this.authenticated();
  }

  /** Entra com usuário e senha; lança `CognitoError` com o motivo da recusa. */
  async signIn(username: string, password: string): Promise<void> {
    this.#setTokens(await this.#cognito.passwordSignIn(username, password));
  }

  /** Access token para a próxima chamada, renovado quando está para vencer; `null` sem sessão. */
  async accessToken(now = Date.now()): Promise<string | null> {
    const tokens = this.#tokens();
    if (!tokens) {
      return null;
    }
    return tokens.expiresAt - EXPIRY_MARGIN_MS > now ? tokens.accessToken : this.refresh();
  }

  /**
   * Renova o access token pelo refresh token, uma renovação por vez; `null` quando o Cognito recusa (sessão
   * revogada ou refresh token vencido), caso em que os tokens saem da aba.
   */
  refresh(): Promise<string | null> {
    this.#refreshing ??= this.#doRefresh().finally(() => (this.#refreshing = null));
    return this.#refreshing;
  }

  /**
   * O servidor recusou o token mesmo depois da renovação: tira os tokens e leva ao login com o aviso de sessão
   * expirada e o endereço atual para a volta. Guarda só esse endereço; as tentativas de envio continuam na partição
   * do usuário, para ele recuperá-las ao entrar de novo.
   */
  expire(): void {
    this.#setTokens(null);
    void this.#router.navigate(['/login'], {
      queryParams: { expired: 1, returnUrl: this.#router.url },
    });
  }

  /**
   * Sai: revoga o refresh token (sem esperar o Cognito responder bem), apaga tudo o que o painel guardou na aba e
   * recarrega a página no login, o que descarta os dados de negócio em memória.
   */
  async logout(): Promise<void> {
    const refreshToken = this.#tokens()?.refreshToken;
    this.#setTokens(null);
    this.#scoped.clearAll();
    if (refreshToken) {
      await this.#cognito.revoke(refreshToken).catch(() => undefined);
    }
    this.#location.assign('/login');
  }

  async #doRefresh(): Promise<string | null> {
    const refreshToken = this.#tokens()?.refreshToken;
    if (!refreshToken) {
      return null;
    }
    try {
      const tokens = await this.#cognito.refresh(refreshToken);
      this.#setTokens(tokens);
      return tokens.accessToken;
    } catch {
      this.#setTokens(null);
      return null;
    }
  }

  #setTokens(tokens: CognitoTokens | null): void {
    this.#tokens.set(tokens);
    try {
      if (tokens) {
        this.#storage?.setItem(TOKENS_KEY, JSON.stringify(tokens));
      } else {
        this.#storage?.removeItem(TOKENS_KEY);
      }
    } catch {
      // NOTE: sem armazenamento, a sessão vale só até recarregar a página.
    }
  }

  #readTokens(): CognitoTokens | null {
    try {
      const raw = this.#storage?.getItem(TOKENS_KEY);
      const value: unknown = raw ? JSON.parse(raw) : null;
      return isTokens(value) ? value : null;
    } catch {
      return null;
    }
  }
}

function isTokens(value: unknown): value is CognitoTokens {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record['accessToken'] === 'string' &&
    typeof record['refreshToken'] === 'string' &&
    typeof record['expiresAt'] === 'number'
  );
}
