import type { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { timeout } from 'rxjs';

import { APP_CONFIG } from '../config/app-config';
import { isApiRequest } from './api-requests';

/**
 * Prazo de cada chamada à API (`requestTimeoutSeconds`). Esgotado, a chamada falha com `TimeoutError`: numa leitura,
 * o polling tenta de novo; numa mutação, o resultado fica desconhecido e a tela oferece o reenvio da mesma intenção.
 */
export const timeoutInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url)) {
    return next(request);
  }
  return next(request).pipe(timeout(inject(APP_CONFIG).requestTimeoutSeconds * 1000));
};
