import { convertToParamMap } from '@angular/router';

import {
  dateRangeError,
  hasFilters,
  quoteListRequest,
  readQuoteListQuery,
} from './quote-list-query';

describe('recorte da lista de cotações (FE02)', () => {
  it('lê os filtros da URL e descarta os que o contrato não conhece', () => {
    const query = readQuoteListQuery(
      convertToParamMap({
        phase: 'QUOTING',
        outcome: 'INVENTADO',
        createdFrom: '12/11/2026',
        limit: '50',
        cursor: 'c2',
      }),
    );

    expect(query).toEqual({
      phase: 'QUOTING',
      outcome: null,
      createdFrom: null,
      createdTo: null,
      limit: 50,
      cursor: 'c2',
    });
  });

  it('converte o período civil nos instantes inclusivos da API', () => {
    const query = readQuoteListQuery(
      convertToParamMap({
        outcome: 'COMPLETED',
        createdFrom: '2026-11-12',
        createdTo: '2026-11-12',
      }),
    );

    expect(quoteListRequest(query)).toEqual({
      limit: 20,
      outcome: 'COMPLETED',
      createdFrom: '2026-11-12T03:00:00.000Z',
      createdTo: '2026-11-13T02:59:59.999Z',
    });
    expect(hasFilters(query)).toBe(true);
  });

  it('recusa o período invertido antes de chamar a API', () => {
    const query = readQuoteListQuery(
      convertToParamMap({ createdFrom: '2026-11-12', createdTo: '2026-11-01' }),
    );

    expect(dateRangeError(query)).toBe('A data final não pode ser anterior à inicial.');
  });
});
