import { readFileSync } from 'node:fs';

/** Troca de uma fixture do contrato. */
export interface Exchange {
  readonly name: string;
  readonly request: { readonly method: string; readonly path: string };
  readonly response: {
    readonly status: number;
    readonly headers?: Record<string, string>;
    readonly body?: unknown;
  };
}

const cache = new Map<string, readonly Exchange[]>();

function load(file: string): readonly Exchange[] {
  let exchanges = cache.get(file);
  if (!exchanges) {
    const url = new URL(`../../../contracts/fixtures/${file}.json`, import.meta.url);
    exchanges = (JSON.parse(readFileSync(url, 'utf8')) as { exchanges: Exchange[] }).exchanges;
    cache.set(file, exchanges);
  }
  return exchanges;
}

/** Resposta da troca `name` de `contracts/fixtures/<file>.json`; lança quando ela não existe. */
export function fixture(file: string, name: string): Exchange['response'] {
  const found = load(file).find((item) => item.name === name);
  if (!found) {
    throw new Error(`Troca "${name}" não existe em contracts/fixtures/${file}.json`);
  }
  return structuredClone(found.response);
}

/** Corpo da troca, para o teste ajustar um campo antes de responder. */
export function fixtureBody<T extends object = Record<string, unknown>>(
  file: string,
  name: string,
): T {
  return fixture(file, name).body as T;
}

export const QUOTE_ID = '5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11';
export const VERSION_1 = `/v1/quotes/${QUOTE_ID}/versions/1`;
