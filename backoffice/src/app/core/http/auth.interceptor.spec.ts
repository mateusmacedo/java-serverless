import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { provideTestApi, settle } from '../../../testing/http';
import { BROWSER_LOCATION } from '../auth/browser-location';

const TOKENS_KEY = 'backoffice.auth';

function storeTokens(expiresAt = Date.now() + 3_600_000): void {
  sessionStorage.setItem(
    TOKENS_KEY,
    JSON.stringify({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresAt }),
  );
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let controller: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
    storeTokens();
    TestBed.configureTestingModule({
      providers: [
        provideTestApi(),
        provideRouter([]),
        { provide: BROWSER_LOCATION, useValue: { assign: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => controller.verify());

  it('põe o token e a correlação só nas chamadas à API', async () => {
    const api = firstValueFrom(http.get('/v1/me'));
    await settle();
    const request = controller.expectOne('/v1/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(request.request.headers.get('X-Correlation-Id')).toMatch(/^[\w-]{36}$/);
    request.flush({});
    await api;

    const config = firstValueFrom(http.get('/config.json'));
    const plain = controller.expectOne('/config.json');
    expect(plain.request.headers.has('Authorization')).toBe(false);
    plain.flush({});
    await config;
  });

  it('no 401, renova o token uma vez e repete a chamada', async () => {
    const call = firstValueFrom(http.get('/v1/quotes'));
    await settle();
    controller
      .expectOne('/v1/quotes')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await settle();

    const refresh = controller.expectOne('/cognito');
    expect(refresh.request.headers.get('X-Amz-Target')).toBe(
      'AWSCognitoIdentityProviderService.InitiateAuth',
    );
    expect(refresh.request.body).toMatchObject({
      AuthFlow: 'REFRESH_TOKEN_AUTH',
      AuthParameters: { REFRESH_TOKEN: 'refresh-1' },
    });
    refresh.flush({ AuthenticationResult: { AccessToken: 'access-2', ExpiresIn: 3600 } });
    await settle();

    const retry = controller.expectOne('/v1/quotes');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ items: [], nextCursor: null });
    await expect(call).resolves.toEqual({ items: [], nextCursor: null });
  });

  it('com a renovação recusada, a sessão expira e leva ao login com o retorno', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const call = firstValueFrom(http.get('/v1/quotes'));
    await settle();
    controller
      .expectOne('/v1/quotes')
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await settle();
    controller
      .expectOne('/cognito')
      .flush(
        { __type: 'NotAuthorizedException', message: 'Refresh Token has expired' },
        { status: 400, statusText: 'Bad Request' },
      );

    await expect(call).rejects.toMatchObject({ status: 401 });
    expect(navigate).toHaveBeenCalledWith(['/login'], {
      queryParams: { expired: 1, returnUrl: '/' },
    });
    expect(sessionStorage.getItem(TOKENS_KEY)).toBeNull();
  });

  it('renova antes de chamar quando o token está para vencer', async () => {
    storeTokens(Date.now() + 10_000);
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideTestApi(), provideRouter([])] });
    http = TestBed.inject(HttpClient);
    controller = TestBed.inject(HttpTestingController);

    const call = firstValueFrom(http.get('/v1/me'));
    await settle();
    controller
      .expectOne('/cognito')
      .flush({ AuthenticationResult: { AccessToken: 'access-2', ExpiresIn: 3600 } });
    await settle();
    const request = controller.expectOne('/v1/me');
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-2');
    request.flush({});
    await call;
  });
});
