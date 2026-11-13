import type { HttpInterceptorFn } from '@angular/common/http';

import { isApiRequest } from './api-requests';

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/**
 * Cada chamada à API leva um id próprio, que o servidor devolve nos erros e registra nos logs; o painel mostra o
 * `correlationId` do erro para o suporte encontrar a chamada.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url) || request.headers.has(CORRELATION_ID_HEADER)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
};
