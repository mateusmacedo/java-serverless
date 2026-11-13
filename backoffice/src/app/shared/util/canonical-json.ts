/**
 * JSON com as chaves dos objetos em ordem, para comparar dois corpos de pedido pelo conteúdo: é o critério do servidor
 * para decidir se o reenvio com a mesma `Idempotency-Key` é o mesmo pedido (JSON canônico, RFC 8785).
 */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(sortKeys(value));
}

function sortKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (typeof value === 'object' && value !== null) {
    return Object.fromEntries(
      Object.keys(value)
        // WHY: a RFC 8785 ordena pelas unidades UTF-16; o `localeCompare` muda a ordem conforme o idioma do navegador.
        .sort(compareCodeUnits)
        .filter((key) => (value as Record<string, unknown>)[key] !== undefined)
        .map((key) => [key, sortKeys((value as Record<string, unknown>)[key])]),
    );
  }
  return value;
}

function compareCodeUnits(a: string, b: string): number {
  if (a === b) {
    return 0;
  }
  return a < b ? -1 : 1;
}
