import type { ParamMap } from '@angular/router';

import type { ListQuotesData, Outcome, Phase } from '../../../shared/api';
import { type PageSize, toPageSize } from '../../../shared/util/cursor';
import { endOfCivilDay, startOfCivilDay } from '../../../shared/util/dates';
import { OUTCOMES, PHASES } from '../quote-presentation';

type ListQuotesQuery = NonNullable<ListQuotesData['query']>;

/** Recorte da lista lido da URL, já normalizado: o que vai para a API e o que a tela mostra. */
export interface QuoteListQuery {
  readonly phase: Phase | null;
  readonly outcome: Outcome | null;
  /** Datas civis de São Paulo ("2026-11-12"), convertidas nos instantes inclusivos que a API recebe. */
  readonly createdFrom: string | null;
  readonly createdTo: string | null;
  readonly limit: PageSize;
  /** Cursor da página aberta; `null` é a primeira página. */
  readonly cursor: string | null;
}

/** Mudança no recorte da URL; `null` tira o parâmetro. */
export type QuoteListChanges = Readonly<
  Partial<Record<keyof QuoteListQuery, string | number | null>>
>;

const CIVIL_DATE = /^\d{4}-\d{2}-\d{2}$/;

export const PHASE_OPTIONS = Object.keys(PHASES) as Phase[];
export const OUTCOME_OPTIONS = Object.keys(OUTCOMES) as Outcome[];

/** Lê o recorte da URL; fase, resultado e datas fora do contrato voltam ao padrão (sem filtro). */
export function readQuoteListQuery(params: ParamMap): QuoteListQuery {
  const phase = params.get('phase');
  const outcome = params.get('outcome');
  return {
    phase: PHASE_OPTIONS.includes(phase as Phase) ? (phase as Phase) : null,
    outcome: OUTCOME_OPTIONS.includes(outcome as Outcome) ? (outcome as Outcome) : null,
    createdFrom: civilDate(params.get('createdFrom')),
    createdTo: civilDate(params.get('createdTo')),
    limit: toPageSize(params.get('limit')),
    cursor: params.get('cursor') || null,
  };
}

/** Período invertido, conferido antes de chamar a API para não gastar uma chamada com um 400. */
export function dateRangeError(query: QuoteListQuery): string | null {
  return query.createdFrom && query.createdTo && query.createdTo < query.createdFrom
    ? 'A data final não pode ser anterior à inicial.'
    : null;
}

/** Parâmetros de `GET /v1/quotes` para o recorte, sem os vazios. */
export function quoteListRequest(query: QuoteListQuery): ListQuotesQuery {
  const createdFrom = startOfCivilDay(query.createdFrom);
  const createdTo = endOfCivilDay(query.createdTo);
  return {
    limit: query.limit,
    ...(query.phase ? { phase: query.phase } : {}),
    ...(query.outcome ? { outcome: query.outcome } : {}),
    ...(createdFrom ? { createdFrom } : {}),
    ...(createdTo ? { createdTo } : {}),
    ...(query.cursor ? { cursor: query.cursor } : {}),
  };
}

export function hasFilters(query: QuoteListQuery): boolean {
  return !!(query.phase || query.outcome || query.createdFrom || query.createdTo);
}

function civilDate(value: string | null): string | null {
  return value && CIVIL_DATE.test(value) ? value : null;
}
