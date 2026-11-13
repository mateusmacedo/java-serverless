import { fixtureBody } from './fixtures';

interface OfferCoverage {
  readonly code: string;
  readonly label: string;
  readonly insuredAmount?: string;
}

interface Offer {
  readonly id: string;
  readonly insurerName: string;
  readonly coverages: readonly OfferCoverage[];
  readonly [key: string]: unknown;
}

/**
 * Ofertas heterogêneas a partir da oferta da alpha da fixture de oferta parcial: a beta tem limite de colisão menor,
 * não inclui incêndio, roubo e furto e não informa franquias.
 */
export function heterogeneousOffers(): {
  revision: number;
  serverTime: string;
  items: Offer[];
  nextCursor: null;
} {
  const page = fixtureBody<{ revision: number; serverTime: string; items: Offer[] }>(
    'quote-partial-offer',
    'ofertas parciais',
  );
  const alpha = page.items[0];
  const beta: Offer = {
    ...alpha,
    id: 'of_beta_e2e',
    insurerId: 'beta',
    insurerName: 'Beta Seguros',
    premiumTotal: '2389.40',
    coverages: alpha.coverages
      .filter((coverage) => coverage.code !== 'FIRE_THEFT')
      .map((coverage) =>
        coverage.code === 'COLLISION' ? { ...coverage, insuredAmount: '75000.00' } : coverage,
      ),
    deductibles: [],
  };
  return {
    revision: page.revision,
    serverTime: page.serverTime,
    items: [alpha, beta],
    nextCursor: null,
  };
}
