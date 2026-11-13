import { type HttpRequest, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting, type TestRequest } from '@angular/common/http/testing';
import type { EnvironmentProviders, Provider } from '@angular/core';

import { APP_CONFIG, type AppConfig } from '../app/core/config/app-config';
import { authInterceptor } from '../app/core/http/auth.interceptor';
import { correlationIdInterceptor } from '../app/core/http/correlation-id.interceptor';
import { provideQuoteApi } from '../app/shared/api';
import { BROWSER_STORAGE } from '../app/shared/state/session-storage';
import { exchange, type FixtureName } from './fixtures';

export const TEST_CONFIG: AppConfig = {
  stage: 'test',
  cognito: { clientId: 'test-client', endpoint: '/cognito' },
  pollIntervalSeconds: 3,
  requestTimeoutSeconds: 30,
};

/** Cliente da API ligado ao `HttpTestingController`, com os interceptors de correlação e de token. */
export function provideTestApi(
  config: Partial<AppConfig> = {},
): (Provider | EnvironmentProviders)[] {
  return [
    { provide: APP_CONFIG, useValue: { ...TEST_CONFIG, ...config } },
    { provide: BROWSER_STORAGE, useValue: window.sessionStorage },
    provideHttpClient(withInterceptors([correlationIdInterceptor, authInterceptor])),
    provideHttpClientTesting(),
    provideQuoteApi(),
  ];
}

/** Espera as promessas do cliente gerado chegarem ao `HttpClient`, que monta a requisição de forma assíncrona. */
export async function settle(rounds = 3): Promise<void> {
  for (let round = 0; round < rounds; round++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

/**
 * Requisição pelo caminho, com ou sem query string: o cliente gerado monta a query na própria URL, e não em
 * `HttpParams`, então a comparação direta com `url` falharia.
 */
export function byPath(path: string, method = 'GET'): (request: HttpRequest<unknown>) => boolean {
  return (request) => request.method === method && request.url.split('?')[0] === path;
}

/** Responde a requisição com a troca da fixture do contrato. */
export function respond(request: TestRequest, file: FixtureName, name: string): void {
  const response = exchange(file, name);
  request.flush(response.body ?? null, {
    status: response.status,
    statusText: response.status >= 400 ? 'Erro' : 'OK',
    headers: response.headers,
  });
}
