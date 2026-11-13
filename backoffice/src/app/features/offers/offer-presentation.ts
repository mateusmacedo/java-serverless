import type { Installment, Offer, Selection } from '../../shared/api';
import { toMillis } from '../../shared/util/dates';
import { compareDecimal, formatMoney } from '../../shared/util/money';
import { type Presentation, UNAVAILABILITY_REASONS } from '../quotes/quote-presentation';

/** Máximo de ofertas na comparação (PRD FE07). */
export const MAX_COMPARED_OFFERS = 3;

export const NOT_INFORMED = 'Não informado';

/**
 * Indica se a validade já passou pelo relógio do servidor estimado (`serverTime` menos o relógio local). É só
 * visual: quem decide se a oferta ainda vale é o servidor, no `selectable` e no `409 OFFER_EXPIRED`.
 */
export function looksExpired(offer: Offer, clockOffsetMs: number, now = Date.now()): boolean {
  const validUntil = toMillis(offer.validUntil);
  return validUntil !== undefined && validUntil <= now + clockOffsetMs;
}

/** Parcelas no formato "1x de R$ 2.450,90 · 10x de R$ 245,09"; sem parcelas, "Não informado". */
export function installmentsText(installments: readonly Installment[], currency: string): string {
  if (!installments.length) {
    return NOT_INFORMED;
  }
  return [...installments]
    .sort((a, b) => a.count - b.count)
    .map((installment) => `${installment.count}x de ${formatMoney(installment.amount, currency)}`)
    .join(' · ');
}

/** Situação da oferta para o badge: selecionada, disponível ou o motivo de não poder ser selecionada. */
export function offerAvailability(
  offer: Offer,
  selection: Selection | null | undefined,
): Presentation {
  if (selection?.offerId === offer.id) {
    return { label: 'Selecionada', color: 'success' };
  }
  if (offer.selectable) {
    return { label: 'Disponível para seleção', color: 'info' };
  }
  return {
    label: offer.unavailabilityReason
      ? UNAVAILABILITY_REASONS[offer.unavailabilityReason]
      : 'Indisponível para seleção',
    color: 'secondary',
  };
}

/** Ofertas pelo prêmio total, do menor ao maior, comparando os decimais sem arredondar; empate pela seguradora. */
export function sortByPremium(offers: readonly Offer[]): Offer[] {
  return [...offers].sort(
    (a, b) =>
      compareDecimal(a.premiumTotal, b.premiumTotal) ||
      a.insurerName.localeCompare(b.insurerName, 'pt-BR'),
  );
}
