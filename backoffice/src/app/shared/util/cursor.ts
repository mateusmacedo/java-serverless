/** Itens por página que o painel oferece: 20 e 50 por preferência de interface e o máximo que a API aceita. */
// WHY: cópia do `maximum` do parâmetro `Limit` do contrato, que o schema gerado não exporta (só os de corpo).
export const PAGE_SIZES = [20, 50, 100] as const;

export type PageSize = (typeof PAGE_SIZES)[number];

export const DEFAULT_PAGE_SIZE: PageSize = 20;

/**
 * Pilha de cursores da paginação Anterior e Próxima: o cursor da página aberta e os das páginas visitadas antes dela.
 * `undefined` é a primeira página. A API só informa o próximo cursor, então voltar depende do que foi visitado.
 */
export interface CursorTrail {
  readonly current: string | undefined;
  readonly previous: readonly (string | undefined)[];
}

/**
 * Começa a pilha na página do cursor. Sem cursor é a primeira página; com cursor, como depois de recarregar a página
 * com o cursor na URL, o histórico se perdeu e Anterior leva à primeira página.
 */
export function startTrail(cursor?: string | null): CursorTrail {
  return cursor ? { current: cursor, previous: [undefined] } : { current: undefined, previous: [] };
}

/** Avança para a página do próximo cursor que a API devolveu. */
export function forward(trail: CursorTrail, nextCursor: string): CursorTrail {
  return { current: nextCursor, previous: [...trail.previous, trail.current] };
}

/** Volta uma página; na primeira página, fica nela. */
export function back(trail: CursorTrail): CursorTrail {
  if (!trail.previous.length) {
    return trail;
  }
  return {
    current: trail.previous.at(-1),
    previous: trail.previous.slice(0, -1),
  };
}

export function canGoBack(trail: CursorTrail): boolean {
  return trail.previous.length > 0;
}

/**
 * Acompanha o cursor que chegou pela URL. O cursor atual mantém a pilha; um cursor já visitado (botão voltar do
 * navegador) recorta a pilha até ele; a primeira página recomeça; outro cursor (botão avançar) entra como seguinte.
 */
export function followCursor(trail: CursorTrail, cursor: string | null | undefined): CursorTrail {
  const target = cursor || undefined;
  if (target === trail.current) {
    return trail;
  }
  if (target === undefined) {
    return startTrail();
  }
  const visited = trail.previous.lastIndexOf(target);
  if (visited >= 0) {
    return { current: target, previous: trail.previous.slice(0, visited) };
  }
  return forward(trail, target);
}

/** Lê o tamanho de página da URL; valor fora da lista volta ao padrão. */
export function toPageSize(value: unknown): PageSize {
  const size = Number(value);
  return (PAGE_SIZES as readonly number[]).includes(size) ? (size as PageSize) : DEFAULT_PAGE_SIZE;
}
