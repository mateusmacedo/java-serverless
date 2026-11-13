import type { Page, Route } from '@playwright/test';

import { fixture } from './fixtures';

/** Resposta simulada: status, corpo JSON e headers. */
export interface MockResponse {
  readonly status: number;
  readonly body?: unknown;
  readonly headers?: Record<string, string>;
}

/** Requisição à API que o painel fez, para as asserções do teste. */
export interface RecordedRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly headers: Record<string, string>;
  readonly body: unknown;
}

type Reply = MockResponse | 'abort';
type Handler = (request: RecordedRequest) => Reply | Promise<Reply>;

interface Registration {
  readonly method: string;
  readonly path: string | RegExp;
  readonly handler: Handler;
  remaining: number;
}

export type Identity = 'corretor' | 'operador' | Record<string, unknown>;

export interface MockApiOptions {
  /** Começa com tokens válidos na aba, sem passar pela tela de login. */
  readonly signedIn?: boolean;
  readonly me?: Identity;
  /** Intervalo padrão do polling; as versões trazem o próprio `retryAfterSeconds`. */
  readonly pollIntervalSeconds?: number;
}

/**
 * API de produto e Cognito simulados com as fixtures do contrato, só para os testes de navegador. Cada teste
 * registra as respostas que a jornada precisa; a mais recente vale primeiro, e `once` responde uma vez só. Uma
 * chamada sem resposta registrada falha o teste no `assertNoUnexpected`.
 */
export class MockApi {
  readonly requests: RecordedRequest[] = [];
  readonly unexpected: string[] = [];
  readonly cognitoCalls: { target: string; body: Record<string, unknown> }[] = [];
  me: Identity;
  refreshFails = false;
  readonly #registrations: Registration[] = [];

  private constructor(options: MockApiOptions) {
    this.me = options.me ?? 'corretor';
  }

  static async attach(page: Page, options: MockApiOptions = {}): Promise<MockApi> {
    const api = new MockApi(options);
    await page.route('**/config.json', (route) =>
      route.fulfill({
        json: {
          stage: 'e2e',
          cognito: { clientId: 'e2e-client', endpoint: '/cognito' },
          pollIntervalSeconds: options.pollIntervalSeconds ?? 1,
          requestTimeoutSeconds: 10,
        },
      }),
    );
    await page.route('**/cognito', (route) => api.#cognito(route));
    await page.route('**/v1/**', (route) => api.#dispatch(route));
    if (options.signedIn) {
      await page.addInitScript(() => {
        // NOTE: só na primeira carga da aba; a saída apaga os tokens, e a recarga no login não pode recolocá-los.
        if (!sessionStorage.getItem('e2e.seeded')) {
          sessionStorage.setItem('e2e.seeded', '1');
          sessionStorage.setItem(
            'backoffice.auth',
            JSON.stringify({
              accessToken: 'e2e-access',
              refreshToken: 'e2e-refresh',
              expiresAt: Date.now() + 3_600_000,
            }),
          );
        }
      });
    }
    api.#defaults();
    return api;
  }

  /** Registra a resposta de `método caminho`; a última registrada vale primeiro. */
  on(method: string, path: string | RegExp, reply: Handler | Reply): this {
    return this.#register(method, path, reply, Number.POSITIVE_INFINITY);
  }

  /** Registra uma resposta que vale uma vez só, como a primeira tentativa que falha. */
  once(method: string, path: string | RegExp, reply: Handler | Reply): this {
    return this.#register(method, path, reply, 1);
  }

  /** Responde com a troca de `contracts/fixtures/<file>.json`. */
  fixture(method: string, path: string | RegExp, file: string, name: string): this {
    return this.on(method, path, fixture(file, name));
  }

  calls(method: string, path: string | RegExp): RecordedRequest[] {
    return this.requests.filter(
      (request) => request.method === method && matches(path, request.path),
    );
  }

  assertNoUnexpected(): void {
    if (this.unexpected.length) {
      throw new Error(`Chamadas sem resposta simulada: ${this.unexpected.join(', ')}`);
    }
  }

  #register(
    method: string,
    path: string | RegExp,
    reply: Handler | Reply,
    remaining: number,
  ): this {
    const handler: Handler = typeof reply === 'function' ? reply : () => reply;
    this.#registrations.push({ method, path, handler, remaining });
    return this;
  }

  #defaults(): void {
    this.on('GET', '/v1/me', () => {
      if (typeof this.me !== 'string') {
        return { status: 200, body: this.me };
      }
      return fixture('me', this.me);
    });
    this.fixture('GET', '/v1/catalog/customers', 'catalog-pages', 'clientes');
    this.fixture('GET', '/v1/catalog/vehicles', 'catalog-pages', 'veículos com busca');
    this.fixture('GET', '/v1/catalog/drivers', 'catalog-pages', 'condutores');
    this.fixture('GET', '/v1/catalog/coverage-profiles', 'catalog-pages', 'perfis de cobertura');
    this.fixture('GET', '/v1/quotes', 'quotes-list', 'primeira página');
  }

  async #dispatch(route: Route): Promise<void> {
    const request = route.request();
    const url = new URL(request.url());
    const recorded: RecordedRequest = {
      method: request.method(),
      path: url.pathname,
      query: url.searchParams,
      headers: request.headers(),
      body: request.postData() ? JSON.parse(request.postData() ?? 'null') : null,
    };
    this.requests.push(recorded);
    const registration = [...this.#registrations]
      .reverse()
      .find(
        (item) =>
          item.remaining > 0 &&
          item.method === recorded.method &&
          matches(item.path, recorded.path),
      );
    if (!registration) {
      this.unexpected.push(`${recorded.method} ${recorded.path}`);
      await route.fulfill({
        status: 501,
        json: { code: 'NOT_MOCKED', message: 'sem resposta', correlationId: 'e2e' },
      });
      return;
    }
    registration.remaining--;
    const reply = await registration.handler(recorded);
    if (reply === 'abort') {
      await route.abort('failed');
      return;
    }
    await route.fulfill({ status: reply.status, json: reply.body ?? null, headers: reply.headers });
  }

  async #cognito(route: Route): Promise<void> {
    const request = route.request();
    const target = (request.headers()['x-amz-target'] ?? '').split('.').pop() ?? '';
    const body = (request.postDataJSON() ?? {}) as Record<string, unknown>;
    this.cognitoCalls.push({ target, body });
    const parameters = (body['AuthParameters'] ?? {}) as Record<string, string>;
    if (target === 'RevokeToken') {
      await route.fulfill({ json: {} });
    } else if (body['AuthFlow'] === 'REFRESH_TOKEN_AUTH' && this.refreshFails) {
      await route.fulfill({
        status: 400,
        json: { __type: 'NotAuthorizedException', message: 'Refresh Token has expired' },
      });
    } else if (parameters['PASSWORD'] === 'senha-errada') {
      await route.fulfill({
        status: 400,
        json: { __type: 'NotAuthorizedException', message: 'Incorrect username or password.' },
      });
    } else {
      await route.fulfill({
        json: {
          AuthenticationResult: {
            AccessToken: 'e2e-access',
            RefreshToken: 'e2e-refresh',
            ExpiresIn: 3600,
          },
        },
      });
    }
  }
}

function matches(path: string | RegExp, value: string): boolean {
  return typeof path === 'string' ? path === value : path.test(value);
}
