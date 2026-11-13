import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';

/** Tokens do user pool: o access token vai à API; o refresh token renova o access token. */
export interface CognitoTokens {
  readonly accessToken: string;
  readonly refreshToken: string;
  /** Instante, em milissegundos, em que o access token vence. */
  readonly expiresAt: number;
}

/** Falha do login ou da renovação, com o tipo do Cognito (`NotAuthorizedException`) ou o do painel. */
export class CognitoError extends Error {
  constructor(
    readonly type: string,
    message: string,
  ) {
    super(message);
    this.name = 'CognitoError';
  }
}

interface AuthenticationResult {
  readonly AccessToken?: string;
  readonly RefreshToken?: string;
  readonly ExpiresIn?: number;
}

interface InitiateAuthResponse {
  readonly AuthenticationResult?: AuthenticationResult;
  readonly ChallengeName?: string;
}

const MESSAGES: Readonly<Record<string, string>> = {
  NotAuthorizedException: 'Usuário ou senha incorretos.',
  UserNotFoundException: 'Usuário ou senha incorretos.',
  PasswordResetRequiredException: 'Sua senha precisa ser redefinida. Fale com o administrador.',
  UserNotConfirmedException: 'Seu usuário ainda não foi confirmado. Fale com o administrador.',
  TooManyRequestsException: 'Muitas tentativas em pouco tempo. Aguarde e tente de novo.',
  LimitExceededException: 'Muitas tentativas em pouco tempo. Aguarde e tente de novo.',
  CHALLENGE:
    'Seu usuário precisa concluir uma etapa de login que este painel ainda não oferece, como a troca da senha provisória. Fale com o administrador.',
  NETWORK: 'Não foi possível falar com o serviço de login. Confira a conexão e tente de novo.',
};

const GENERIC = 'Não foi possível entrar agora. Tente de novo.';

/**
 * APIs públicas do Cognito que o painel usa, pelo protocolo JSON do serviço: login com usuário e senha
 * (`USER_PASSWORD_AUTH`, o fluxo que o app client permite sem segredo), renovação pelo refresh token e revogação na
 * saída. As chamadas vão ao `cognito.endpoint` da configuração, no mesmo endereço do painel.
 */
@Injectable({ providedIn: 'root' })
export class CognitoClient {
  readonly #http = inject(HttpClient);
  readonly #config = inject(APP_CONFIG);

  async passwordSignIn(
    username: string,
    password: string,
    now = Date.now(),
  ): Promise<CognitoTokens> {
    const response = await this.#call<InitiateAuthResponse>('InitiateAuth', {
      AuthFlow: 'USER_PASSWORD_AUTH',
      ClientId: this.#config.cognito.clientId,
      AuthParameters: { USERNAME: username, PASSWORD: password },
    });
    return tokensOf(response, undefined, now);
  }

  async refresh(refreshToken: string, now = Date.now()): Promise<CognitoTokens> {
    const response = await this.#call<InitiateAuthResponse>('InitiateAuth', {
      AuthFlow: 'REFRESH_TOKEN_AUTH',
      ClientId: this.#config.cognito.clientId,
      AuthParameters: { REFRESH_TOKEN: refreshToken },
    });
    return tokensOf(response, refreshToken, now);
  }

  async revoke(refreshToken: string): Promise<void> {
    await this.#call('RevokeToken', {
      Token: refreshToken,
      ClientId: this.#config.cognito.clientId,
    });
  }

  async #call<T>(operation: string, body: object): Promise<T> {
    try {
      return await firstValueFrom(
        this.#http.post<T>(this.#config.cognito.endpoint, body, {
          headers: {
            'Content-Type': 'application/x-amz-json-1.1',
            'X-Amz-Target': `AWSCognitoIdentityProviderService.${operation}`,
          },
        }),
      );
    } catch (error) {
      throw toCognitoError(error);
    }
  }
}

/** Texto do erro de login para a tela. */
export function signInMessage(error: unknown): string {
  return error instanceof CognitoError ? (MESSAGES[error.type] ?? GENERIC) : GENERIC;
}

function tokensOf(
  response: InitiateAuthResponse,
  refreshToken: string | undefined,
  now: number,
): CognitoTokens {
  const result = response.AuthenticationResult;
  if (response.ChallengeName || !result?.AccessToken) {
    throw new CognitoError('CHALLENGE', MESSAGES['CHALLENGE']);
  }
  const refresh = result.RefreshToken ?? refreshToken;
  if (!refresh) {
    throw new CognitoError('NO_REFRESH_TOKEN', GENERIC);
  }
  return {
    accessToken: result.AccessToken,
    refreshToken: refresh,
    expiresAt: now + (result.ExpiresIn ?? 3600) * 1000,
  };
}

function toCognitoError(error: unknown): CognitoError {
  if (!(error instanceof HttpErrorResponse) || error.status === 0) {
    return new CognitoError('NETWORK', MESSAGES['NETWORK']);
  }
  const body: unknown = error.error;
  const rawType =
    (typeof body === 'object' &&
    body !== null &&
    typeof (body as Record<string, unknown>)['__type'] === 'string'
      ? ((body as Record<string, unknown>)['__type'] as string)
      : error.headers.get('x-amzn-ErrorType')) ?? `HTTP_${error.status}`;
  // NOTE: o tipo pode vir qualificado ("com.amazonaws...#NotAuthorizedException") ou com sufixo (":http://...").
  const type = rawType.split('#').pop()?.split(':')[0] ?? rawType;
  return new CognitoError(type, MESSAGES[type] ?? GENERIC);
}
