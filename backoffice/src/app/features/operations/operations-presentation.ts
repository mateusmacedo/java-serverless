import type { ParamMap } from '@angular/router';

import type {
  IssueStatus,
  IssueType,
  ListOperationIssuesData,
  OperationStatus,
} from '../../shared/api';
import { type PageSize, toPageSize } from '../../shared/util/cursor';
import type { Presentation } from '../quotes/quote-presentation';

type ListIssuesQuery = NonNullable<ListOperationIssuesData['query']>;

export const ISSUE_TYPES = {
  UNKNOWN_QUERY: 'Consulta sem resposta',
  UNMATCHED_CALLBACK: 'Retorno sem correlação',
  DEAD_LETTER: 'Mensagem descartada',
  STALE_OUTBOX: 'Evento não publicado',
  WORKFLOW_FAILED: 'Processamento interrompido',
  TASK_EXPIRED: 'Espera expirada',
  STUCK_VERSION: 'Versão parada',
} as const satisfies Record<IssueType, string>;

export const ISSUE_STATUSES = {
  OPEN: { label: 'Aberta', color: 'warning' },
  RESOLVED: { label: 'Resolvida', color: 'success' },
} as const satisfies Record<IssueStatus, Presentation>;

export const OPERATION_STATUSES = {
  QUEUED: { label: 'Na fila', color: 'secondary' },
  RUNNING: { label: 'Em execução', color: 'info' },
  SUCCEEDED: { label: 'Concluída com sucesso', color: 'success' },
  FAILED: { label: 'Concluída sem sucesso', color: 'danger' },
} as const satisfies Record<OperationStatus, Presentation>;

export const ISSUE_TYPE_OPTIONS = Object.keys(ISSUE_TYPES) as IssueType[];
export const ISSUE_STATUS_OPTIONS = Object.keys(ISSUE_STATUSES) as IssueStatus[];

export function isOperationFinished(status: OperationStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED';
}

/** Recorte da lista de ocorrências lido da URL. */
export interface IssueListQuery {
  readonly type: IssueType | null;
  readonly status: IssueStatus | null;
  readonly limit: PageSize;
  readonly cursor: string | null;
}

export function readIssueListQuery(params: ParamMap): IssueListQuery {
  const type = params.get('type');
  const status = params.get('status');
  return {
    type: ISSUE_TYPE_OPTIONS.includes(type as IssueType) ? (type as IssueType) : null,
    status: ISSUE_STATUS_OPTIONS.includes(status as IssueStatus) ? (status as IssueStatus) : null,
    limit: toPageSize(params.get('limit')),
    cursor: params.get('cursor') || null,
  };
}

export function issueListRequest(query: IssueListQuery): ListIssuesQuery {
  return {
    limit: query.limit,
    ...(query.type ? { type: query.type } : {}),
    ...(query.status ? { status: query.status } : {}),
    ...(query.cursor ? { cursor: query.cursor } : {}),
  };
}
