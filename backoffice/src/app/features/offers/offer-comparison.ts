import type { Offer } from '../../shared/api';
import { formatDateTime } from '../../shared/util/dates';
import { formatMoney, sameDecimal } from '../../shared/util/money';
import { installmentsText, NOT_INFORMED } from './offer-presentation';

/**
 * Valor de uma oferta numa linha: um valor (que pode ser zero), a cobertura que a oferta não inclui ou o dado que a
 * seguradora não informou. Os três são estados diferentes e nunca se confundem.
 */
export type CellKind = 'value' | 'not-included' | 'not-informed';

export interface ComparisonCell {
  readonly kind: CellKind;
  readonly text: string;
  /** Decimal exato do contrato, para conferir divergência sem arredondar. */
  readonly amount?: string;
}

export interface ComparisonRow {
  readonly key: string;
  readonly label: string;
  readonly group: 'price' | 'coverage' | 'deductible' | 'validity';
  readonly cells: readonly ComparisonCell[];
  /** Limite ou franquia diferente entre as ofertas, inclusive quando uma não inclui ou não informa. */
  readonly divergent: boolean;
}

const NOT_INCLUDED = 'Não incluída';

/**
 * Linhas da comparação pelos códigos normalizados de cobertura: prêmio, parcelas, validade, uma linha por cobertura
 * (importância segurada) e uma por franquia, na ordem em que aparecem nas ofertas. A divergência só é marcada em
 * cobertura e franquia, como o PRD pede; o painel não infere equivalência entre códigos diferentes.
 */
export function buildComparison(offers: readonly Offer[]): ComparisonRow[] {
  const coverageLabels = new Map<string, string>();
  for (const offer of offers) {
    for (const coverage of offer.coverages) {
      if (!coverageLabels.has(coverage.code)) {
        coverageLabels.set(coverage.code, coverage.label);
      }
    }
  }
  const deductibleCodes: string[] = [];
  for (const offer of offers) {
    for (const deductible of offer.deductibles) {
      if (!deductibleCodes.includes(deductible.coverageCode)) {
        deductibleCodes.push(deductible.coverageCode);
      }
    }
  }

  const rows: ComparisonRow[] = [
    {
      key: 'premium',
      label: 'Prêmio total',
      group: 'price',
      cells: offers.map((offer) =>
        value(formatMoney(offer.premiumTotal, offer.currency), offer.premiumTotal),
      ),
      divergent: false,
    },
    {
      key: 'installments',
      label: 'Parcelamento',
      group: 'price',
      cells: offers.map((offer) =>
        offer.installments.length
          ? value(installmentsText(offer.installments, offer.currency))
          : { kind: 'not-informed', text: NOT_INFORMED },
      ),
      divergent: false,
    },
    {
      key: 'validity',
      label: 'Válida até',
      group: 'validity',
      cells: offers.map((offer) => value(formatDateTime(offer.validUntil))),
      divergent: false,
    },
  ];

  for (const [code, label] of coverageLabels) {
    const cells = offers.map((offer): ComparisonCell => {
      const coverage = offer.coverages.find((item) => item.code === code);
      if (!coverage) {
        return { kind: 'not-included', text: NOT_INCLUDED };
      }
      return coverage.insuredAmount === undefined
        ? { kind: 'not-informed', text: NOT_INFORMED }
        : value(formatMoney(coverage.insuredAmount, offer.currency), coverage.insuredAmount);
    });
    rows.push({
      key: `coverage:${code}`,
      label,
      group: 'coverage',
      cells,
      divergent: diverges(cells),
    });
  }

  for (const code of deductibleCodes) {
    const cells = offers.map((offer): ComparisonCell => {
      const deductible = offer.deductibles.find((item) => item.coverageCode === code);
      return deductible
        ? value(formatMoney(deductible.amount, offer.currency), deductible.amount)
        : { kind: 'not-informed', text: NOT_INFORMED };
    });
    rows.push({
      key: `deductible:${code}`,
      label: `Franquia de ${coverageLabels.get(code) ?? code}`,
      group: 'deductible',
      cells,
      divergent: diverges(cells),
    });
  }
  return rows;
}

function value(text: string, amount?: string): ComparisonCell {
  return amount === undefined ? { kind: 'value', text } : { kind: 'value', text, amount };
}

function diverges(cells: readonly ComparisonCell[]): boolean {
  if (cells.length < 2) {
    return false;
  }
  const [first, ...rest] = cells;
  return rest.some(
    (cell) =>
      cell.kind !== first.kind ||
      (cell.amount !== undefined &&
        first.amount !== undefined &&
        !sameDecimal(cell.amount, first.amount)),
  );
}
