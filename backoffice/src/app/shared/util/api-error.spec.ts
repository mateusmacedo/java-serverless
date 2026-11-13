import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { exchange } from '../../../testing/fixtures';
import {
  apiData,
  errorMessage,
  fieldErrorMap,
  isConflict,
  isRetryableRejection,
  isUnknownOutcome,
  NETWORK_ERROR,
  TIMEOUT,
  toApiError,
} from './api-error';

function httpError(file: Parameters<typeof exchange>[0], name: string): HttpErrorResponse {
  const response = exchange(file, name);
  return new HttpErrorResponse({
    status: response.status,
    error: response.body,
    headers: new HttpHeaders(response.headers ?? {}),
  });
}

describe('toApiError', () => {
  it('lê o corpo do contrato, o status e os campos inválidos do 400', () => {
    const error = toApiError(httpError('errors', 'validação'));

    expect(error.status).toBe(400);
    expect(error.code).toBe('VALIDATION_ERROR');
    expect(error.correlationId).toBe('7d1c2a9e-1f6b-4c3a-9e8d-0a1b2c3d4e5f');
    expect(fieldErrorMap(error)).toEqual({
      'risk.usage': 'Use PERSONAL ou COMMERCIAL.',
      desiredStartDate: 'Use uma data válida no formato YYYY-MM-DD.',
    });
  });

  it('guarda o Retry-After do 429', () => {
    const error = toApiError(httpError('errors', 'quota excedida'));

    expect(error.retryAfterSeconds).toBe(5);
    expect(errorMessage(error)).toBe(
      'Muitas solicitações em pouco tempo. Aguarde 5 segundos e tente de novo.',
    );
    expect(isRetryableRejection(error)).toBe(true);
  });

  it('trata a falha sem resposta como rede e o TimeoutError como prazo', () => {
    expect(toApiError(new HttpErrorResponse({ status: 0 })).code).toBe(NETWORK_ERROR);
    expect(
      toApiError(Object.assign(new Error('Timeout has occurred'), { name: 'TimeoutError' })).code,
    ).toBe(TIMEOUT);
  });

  it('não expõe o código cru quando o corpo não segue o contrato', () => {
    const error = toApiError(
      new HttpErrorResponse({ status: 502, error: '<html>Bad gateway</html>' }),
    );

    expect(error.code).toBe('HTTP_502');
    expect(errorMessage(error)).not.toContain('HTTP_502');
  });
});

describe('situação da mutação pelo erro', () => {
  it('considera desconhecido o resultado sem resposta ou com gateway sem a resposta da função', () => {
    expect(isUnknownOutcome(toApiError(new HttpErrorResponse({ status: 0 })))).toBe(true);
    expect(isUnknownOutcome(toApiError(new HttpErrorResponse({ status: 504 })))).toBe(true);
    expect(isUnknownOutcome(toApiError(httpError('errors', 'validação')))).toBe(false);
  });

  it('trata o 503 antes da aceitação como reenvio com a mesma chave', () => {
    const error = toApiError(httpError('errors', 'indisponível antes da aceitação'));

    expect(isRetryableRejection(error)).toBe(true);
    expect(errorMessage(error)).not.toContain('Idempotency-Key');
  });

  it('troca a mensagem técnica dos conflitos pela do painel', () => {
    const conflict = toApiError(httpError('conflicts', 'expectedVersion desatualizado'));

    expect(isConflict(conflict, 'VERSION_CONFLICT')).toBe(true);
    expect(errorMessage(conflict)).not.toContain('expectedVersion');
    expect(errorMessage(conflict)).toContain('outra versão foi criada');
  });
});

describe('apiData', () => {
  it('devolve os dados ou lança o erro normalizado pela resposta', async () => {
    await expect(apiData(Promise.resolve({ data: { ok: true } }))).resolves.toEqual({ ok: true });
    await expect(
      apiData(
        Promise.resolve({
          error: { code: 'FORBIDDEN' },
          response: httpError('errors', 'seleção sem offers:select'),
        }),
      ),
    ).rejects.toMatchObject({ kind: 'api-error', status: 403, code: 'FORBIDDEN' });
  });
});
