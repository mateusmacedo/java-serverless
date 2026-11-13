import {
  HttpErrorResponse,
  type HttpHandlerFn,
  type HttpInterceptorFn,
  type HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { AuthSession } from '../auth/auth-session';
import { isApiRequest } from './api-requests';

/**
 * Põe o access token nas chamadas à API. No 401, renova o token uma vez e repete a chamada, que é segura também nas
 * mutações, porque elas levam a mesma `Idempotency-Key`; se o servidor recusar de novo, a sessão expirou.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url)) {
    return next(request);
  }
  const auth = inject(AuthSession);
  return from(auth.accessToken()).pipe(
    switchMap((token) => sendWithToken(request, token, auth, next)),
  );
};

function sendWithToken<T>(
  request: HttpRequest<T>,
  token: string | null,
  auth: AuthSession,
  next: HttpHandlerFn,
) {
  return next(withToken(request, token)).pipe(
    catchError((error: unknown) => handleRequestError(error, request, token, auth, next)),
  );
}

function handleRequestError<T>(
  error: unknown,
  request: HttpRequest<T>,
  token: string | null,
  auth: AuthSession,
  next: HttpHandlerFn,
) {
  if (!isUnauthorized(error) || token === null) {
    return throwError(() => error);
  }
  return from(auth.refresh()).pipe(
    switchMap((fresh) => retryWithFreshToken(fresh, error, request, auth, next)),
  );
}

function retryWithFreshToken<T>(
  fresh: string | null,
  originalError: unknown,
  request: HttpRequest<T>,
  auth: AuthSession,
  next: HttpHandlerFn,
) {
  if (fresh === null) {
    auth.expire();
    return throwError(() => originalError);
  }
  return next(withToken(request, fresh)).pipe(
    catchError((retryError: unknown) => handleRetryError(retryError, auth)),
  );
}

function handleRetryError(error: unknown, auth: AuthSession) {
  if (isUnauthorized(error)) {
    auth.expire();
  }
  return throwError(() => error);
}

function withToken<T>(request: HttpRequest<T>, token: string | null): HttpRequest<T> {
  return token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request;
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof HttpErrorResponse && error.status === 401;
}
