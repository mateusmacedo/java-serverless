import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { provideTestApi, settle } from '../../../testing/http';
import { SessionScopedStorage } from '../../shared/state/session-storage';
import { AuthSession } from './auth-session';
import { BROWSER_LOCATION } from './browser-location';
import { CognitoError, signInMessage } from './cognito-client';

describe('AuthSession', () => {
  let auth: AuthSession;
  let controller: HttpTestingController;
  const location = { assign: vi.fn() };

  beforeEach(() => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
    location.assign.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideTestApi(),
        provideRouter([]),
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    auth = TestBed.inject(AuthSession);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => controller.verify());

  it('entra com USER_PASSWORD_AUTH do app client e guarda os tokens só na aba', async () => {
    const signIn = auth.signIn('corretor', 'Senha#Segura1');
    await settle();
    const request = controller.expectOne('/cognito');
    expect(request.request.body).toEqual({
      AuthFlow: 'USER_PASSWORD_AUTH',
      ClientId: 'test-client',
      AuthParameters: { USERNAME: 'corretor', PASSWORD: 'Senha#Segura1' },
    });
    request.flush({
      AuthenticationResult: { AccessToken: 'a', RefreshToken: 'r', ExpiresIn: 3600 },
    });
    await signIn;

    expect(auth.isAuthenticated()).toBe(true);
    expect(JSON.parse(sessionStorage.getItem('backoffice.auth') ?? '{}')).toMatchObject({
      accessToken: 'a',
    });
    expect(localStorage.getItem('backoffice.auth')).toBeNull();
  });

  it('traduz a recusa do Cognito sem revelar se o usuário existe', async () => {
    const signIn = auth.signIn('corretor', 'errada');
    await settle();
    controller
      .expectOne('/cognito')
      .flush(
        { __type: 'NotAuthorizedException', message: 'Incorrect username or password.' },
        { status: 400, statusText: 'Bad Request' },
      );

    const error = await signIn.catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(CognitoError);
    expect(signInMessage(error)).toBe('Usuário ou senha incorretos.');
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('trata o desafio de troca de senha como etapa não oferecida', async () => {
    const signIn = auth.signIn('novo', 'Provisoria#123');
    await settle();
    controller
      .expectOne('/cognito')
      .flush({ ChallengeName: 'NEW_PASSWORD_REQUIRED', Session: 's' });

    expect(signInMessage(await signIn.catch((thrown: unknown) => thrown))).toContain(
      'troca da senha provisória',
    );
  });

  it('faz uma renovação só para chamadas simultâneas', async () => {
    sessionStorage.setItem(
      'backoffice.auth',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: 0 }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideTestApi(),
        provideRouter([]),
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    auth = TestBed.inject(AuthSession);
    controller = TestBed.inject(HttpTestingController);

    const both = Promise.all([auth.accessToken(), auth.accessToken()]);
    await settle();
    controller
      .expectOne('/cognito')
      .flush({ AuthenticationResult: { AccessToken: 'novo', ExpiresIn: 3600 } });

    await expect(both).resolves.toEqual(['novo', 'novo']);
  });

  it('a saída revoga o refresh token, apaga a aba e recarrega no login (FT13)', async () => {
    sessionStorage.setItem(
      'backoffice.auth',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 3_600_000 }),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideTestApi(),
        provideRouter([]),
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    auth = TestBed.inject(AuthSession);
    controller = TestBed.inject(HttpTestingController);
    const scoped = TestBed.inject(SessionScopedStorage);
    scoped.usePartition('ana', 'org-acme');
    scoped.write('attempt.create-quote', { key: 'k' });

    const logout = auth.logout();
    await settle();
    const revoke = controller.expectOne('/cognito');
    expect(revoke.request.headers.get('X-Amz-Target')).toBe(
      'AWSCognitoIdentityProviderService.RevokeToken',
    );
    revoke.flush({});
    await logout;

    expect(sessionStorage.length).toBe(0);
    expect(auth.isAuthenticated()).toBe(false);
    expect(location.assign).toHaveBeenCalledWith('/login');
  });
});
