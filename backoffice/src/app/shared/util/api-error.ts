/** Campo inválido de um `400`, com o caminho no corpo do pedido (`risk.vehicleRef`). */
export interface ApiFieldError {
  readonly path: string;
  readonly message: string;
}

/**
 * Erro de uma chamada à API, já normalizado: o corpo do contrato (`code`, `message`, `correlationId` e
 * `fieldErrors`), o status HTTP e o `Retry-After` do `429`. Sem resposta do servidor, `status` é 0 e `code` é
 * `NETWORK_ERROR` ou `TIMEOUT`.
 */
export interface ApiError {
  readonly kind: 'api-error';
  readonly status: number;
  readonly code: string;
  readonly message?: string;
  readonly correlationId?: string;
  readonly fieldErrors: readonly ApiFieldError[];
  readonly retryAfterSeconds?: number;
}

export const NETWORK_ERROR = 'NETWORK_ERROR';
export const TIMEOUT = 'TIMEOUT';

/** Conflitos que o contrato devolve no `409` (`ConflictError`). */
export type ConflictCode =
  | 'IDEMPOTENCY_CONFLICT'
  | 'VERSION_CONFLICT'
  | 'OFFER_EXPIRED'
  | 'VERSION_SUPERSEDED'
  | 'SELECTION_ALREADY_EXISTS'
  | 'QUOTE_ALREADY_SELECTED';

// WHY: as mensagens do servidor para estes códigos citam `expectedVersion` e `Idempotency-Key`, termos técnicos
// que o PRD (§11) não quer na tela do usuário comercial; a lista de códigos é fechada no contrato.
const CONFLICT_MESSAGES: Readonly<Record<ConflictCode, string>> = {
  IDEMPOTENCY_CONFLICT:
    'Esta solicitação já tinha sido enviada com outros dados. Revise os campos e envie de novo.',
  VERSION_CONFLICT:
    'A cotação mudou depois que você abriu esta tela: outra versão foi criada. Os dados que você preencheu continuam nos campos; consulte a versão atual antes de enviar.',
  OFFER_EXPIRED:
    'A oferta expirou antes da confirmação. Nenhuma intenção foi registrada; consulte as ofertas atualizadas.',
  VERSION_SUPERSEDED:
    'Esta versão foi substituída por uma mais nova. Nenhuma intenção foi registrada; consulte a versão atual.',
  SELECTION_ALREADY_EXISTS:
    'A cotação já tem outra oferta selecionada, e a seleção é definitiva. Nenhuma nova intenção foi registrada.',
  QUOTE_ALREADY_SELECTED: 'A cotação já tem uma oferta selecionada e não aceita nova versão.',
};

const STATUS_MESSAGES: Readonly<Record<number, string>> = {
  401: 'Sua sessão expirou. Entre de novo para continuar.',
  403: 'Você não tem permissão para esta ação.',
  404: 'Não encontramos este registro, ou ele não está disponível para a sua organização.',
  503: 'O serviço não conseguiu registrar o pedido agora. Tente de novo: o pedido não será duplicado.',
};

/** Último recurso, quando nem o servidor nem o painel têm texto: o código cru nunca aparece. */
export const GENERIC_ERROR_MESSAGE = 'Não foi possível concluir a operação.';

/** Normaliza o erro de uma chamada: o erro do `HttpClient`, o `TimeoutError` do rxjs ou um erro já normalizado. */
export function toApiError(error: unknown): ApiError {
  if (isApiError(error)) {
    return error;
  }
  if (isRecord(error) && error['name'] === 'TimeoutError') {
    return blank(0, TIMEOUT);
  }
  if (isRecord(error) && typeof error['status'] === 'number' && 'error' in error) {
    return fromResponse(error['status'], error['error'], error['headers']);
  }
  return blank(0, NETWORK_ERROR);
}

/** Resultado de uma chamada do cliente gerado sem `throwOnError`: os dados ou, na falha, o erro e a resposta. */
export interface ApiResult<T> {
  readonly data?: T;
  readonly error?: unknown;
  readonly response?: unknown;
}

/**
 * Devolve os dados da chamada do cliente gerado ou lança o erro dela já normalizado (`toApiError`). A falha é
 * reconhecida pela chave `error`, que o cliente só põe no resultado quando a chamada falha.
 */
export async function apiData<T>(call: Promise<ApiResult<T>>): Promise<T> {
  const result = await call;
  if ('error' in result) {
    throw toApiError(result.response ?? result.error);
  }
  return result.data as T;
}

export function isApiError(value: unknown): value is ApiError {
  return isRecord(value) && value['kind'] === 'api-error';
}

/** Indica conflito do contrato com o código dado. */
export function isConflict(error: ApiError | null | undefined, code: ConflictCode): boolean {
  return error?.status === 409 && error.code === code;
}

/**
 * O servidor pode ter gravado a mutação sem que a resposta chegasse: rede fora, prazo excedido ou gateway sem a
 * resposta da função. Só o reenvio com a mesma chave e o mesmo body esclarece.
 */
export function isUnknownOutcome(error: ApiError | null | undefined): boolean {
  return !!error && (error.status === 0 || error.status === 502 || error.status === 504);
}

/**
 * O servidor recusou antes de aceitar duravelmente e pede o reenvio com a mesma chave: `503` (indisponível) ou `429`
 * (quota, depois do `Retry-After`).
 */
export function isRetryableRejection(error: ApiError | null | undefined): boolean {
  return !!error && (error.status === 503 || error.status === 429);
}

/** Indica que a chamada nem chegou a uma resposta do servidor, como numa consulta que falhou por rede. */
export function isNetworkError(error: ApiError | null | undefined): boolean {
  return !!error && error.status === 0;
}

/** Texto do erro para a tela, sem código cru: o do painel para os casos dele e o do servidor nos demais. */
export function errorMessage(error: ApiError | null | undefined): string {
  if (!error) {
    return '';
  }
  if (error.code === NETWORK_ERROR) {
    return 'Não foi possível falar com o servidor. Confira a conexão e tente de novo.';
  }
  if (error.code === TIMEOUT) {
    return 'O servidor demorou demais para responder.';
  }
  if (error.status === 409 && error.code in CONFLICT_MESSAGES) {
    return CONFLICT_MESSAGES[error.code as ConflictCode];
  }
  if (error.status === 429) {
    const wait = error.retryAfterSeconds;
    return wait
      ? `Muitas solicitações em pouco tempo. Aguarde ${wait} segundos e tente de novo.`
      : 'Muitas solicitações em pouco tempo. Aguarde um pouco e tente de novo.';
  }
  if (error.status === 401 || error.status === 503) {
    return STATUS_MESSAGES[error.status];
  }
  return (
    nonBlank(error.message) ??
    STATUS_MESSAGES[error.status] ??
    (error.status >= 500
      ? 'O servidor não conseguiu concluir a operação. Tente de novo em instantes.'
      : GENERIC_ERROR_MESSAGE)
  );
}

/** Primeira mensagem de cada campo inválido, pelo caminho do contrato, para mostrar embaixo do campo. */
export function fieldErrorMap(error: ApiError | null | undefined): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const { path, message } of error?.fieldErrors ?? []) {
    errors[path] ??= message;
  }
  return errors;
}

function fromResponse(status: number, body: unknown, headers: unknown): ApiError {
  if (status === 0) {
    return blank(0, NETWORK_ERROR);
  }
  const retryAfterSeconds = retryAfter(headers);
  const extra = retryAfterSeconds === undefined ? {} : { retryAfterSeconds };
  if (isRecord(body) && typeof body['code'] === 'string') {
    return {
      ...blank(status, body['code']),
      ...extra,
      message: typeof body['message'] === 'string' ? body['message'] : undefined,
      correlationId: typeof body['correlationId'] === 'string' ? body['correlationId'] : undefined,
      fieldErrors: Array.isArray(body['fieldErrors'])
        ? body['fieldErrors'].filter(isFieldError)
        : [],
    };
  }
  return { ...blank(status, `HTTP_${status}`), ...extra };
}

function retryAfter(headers: unknown): number | undefined {
  if (!isRecord(headers) || typeof headers['get'] !== 'function') {
    return undefined;
  }
  const value = Number(
    (headers as unknown as { get(name: string): string | null }).get('Retry-After'),
  );
  return Number.isInteger(value) && value > 0 ? value : undefined;
}

function blank(status: number, code: string): ApiError {
  return { kind: 'api-error', status, code, fieldErrors: [] };
}

function isFieldError(value: unknown): value is ApiFieldError {
  return (
    isRecord(value) && typeof value['path'] === 'string' && typeof value['message'] === 'string'
  );
}

function nonBlank(text: string | undefined): string | undefined {
  return text?.trim() ? text : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
