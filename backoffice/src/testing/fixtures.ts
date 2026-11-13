import catalogPages from '../../../contracts/fixtures/catalog-pages.json';
import conflicts from '../../../contracts/fixtures/conflicts.json';
import errors from '../../../contracts/fixtures/errors.json';
import me from '../../../contracts/fixtures/me.json';
import offerExpiration from '../../../contracts/fixtures/offer-expiration.json';
import operations from '../../../contracts/fixtures/operations.json';
import quoteDeclined from '../../../contracts/fixtures/quote-declined.json';
import quoteNeedsInformation from '../../../contracts/fixtures/quote-needs-information.json';
import quotePartialNeedsInformation from '../../../contracts/fixtures/quote-partial-needs-information.json';
import quotePartialOffer from '../../../contracts/fixtures/quote-partial-offer.json';
import quoteProgress from '../../../contracts/fixtures/quote-progress.json';
import quoteUnavailable from '../../../contracts/fixtures/quote-unavailable.json';
import quotesList from '../../../contracts/fixtures/quotes-list.json';
import selection from '../../../contracts/fixtures/selection.json';

/** Troca de uma fixture do contrato: o pedido e a resposta que o servidor dá a ele. */
interface Exchange {
  readonly name: string;
  readonly response: {
    readonly status: number;
    readonly headers?: Record<string, string>;
    readonly body?: unknown;
  };
}

interface FixtureFile {
  readonly exchanges: readonly Exchange[];
}

/**
 * Fixtures de `contracts/fixtures`, as mesmas que os testes do backend validam contra o `openapi.yaml`. Os testes do
 * painel usam só elas como respostas simuladas; o app em execução sempre fala com a API real.
 */
export const FIXTURES = {
  catalogPages,
  conflicts,
  errors,
  me,
  offerExpiration,
  operations,
  quoteDeclined,
  quoteNeedsInformation,
  quotePartialNeedsInformation,
  quotePartialOffer,
  quoteProgress,
  quoteUnavailable,
  quotesList,
  selection,
} as const satisfies Record<string, FixtureFile>;

export type FixtureName = keyof typeof FIXTURES;

/** Resposta da troca `name` da fixture; lança quando a troca não existe, para o teste não passar em branco. */
export function exchange(file: FixtureName, name: string): Exchange['response'] {
  const found = (FIXTURES[file] as FixtureFile).exchanges.find((item) => item.name === name);
  if (!found) {
    throw new Error(`Troca "${name}" não existe em contracts/fixtures/${file}`);
  }
  return found.response;
}

/** Corpo da resposta da troca, com o tipo do contrato que o teste espera. */
export function body<T>(file: FixtureName, name: string): T {
  return structuredClone(exchange(file, name).body) as T;
}
