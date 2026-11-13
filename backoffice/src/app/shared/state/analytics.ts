import { DOCUMENT, inject, Injectable } from '@angular/core';

/** Eventos de interface do PRD (§12); nenhum leva dado pessoal, só ids opacos e códigos. */
export type AnalyticsEvent =
  | 'QuoteFormStarted'
  | 'QuoteSubmissionAccepted'
  | 'FirstOfferDisplayed'
  | 'OfferComparisonOpened'
  | 'OfferSelectionConfirmed'
  | 'UiRecoveryRequested';

/** Propriedades aceitas: ids opacos, versão, contagens e códigos do contrato. */
export interface AnalyticsProperties {
  readonly quoteId?: string;
  readonly version?: number;
  readonly offerCount?: number;
  readonly code?: string;
}

/** Nome do evento do DOM que leva cada métrica, para o coletor do ambiente ouvir sem acoplar o painel a um fornecedor. */
export const ANALYTICS_DOM_EVENT = 'backoffice:analytics';

export interface AnalyticsRecord extends AnalyticsProperties {
  readonly event: AnalyticsEvent;
  readonly at: string;
}

/**
 * Métricas de interface. Cada evento sai como `CustomEvent` no `window`, com a lista fechada de propriedades acima;
 * sem coletor configurado, nada sai do navegador. A seleção só é registrada depois da resposta do servidor.
 */
@Injectable({ providedIn: 'root' })
export class Analytics {
  readonly #view = inject(DOCUMENT).defaultView;
  readonly #once = new Set<string>();

  track(event: AnalyticsEvent, properties: AnalyticsProperties = {}): void {
    const detail: AnalyticsRecord = {
      event,
      at: new Date().toISOString(),
      ...pick(properties),
    };
    this.#view?.dispatchEvent(new CustomEvent(ANALYTICS_DOM_EVENT, { detail }));
  }

  /** Emite uma vez por chave na sessão da página, como a primeira oferta exibida de cada versão. */
  trackOnce(key: string, event: AnalyticsEvent, properties: AnalyticsProperties = {}): void {
    if (this.#once.has(key)) {
      return;
    }
    this.#once.add(key);
    this.track(event, properties);
  }
}

function pick(properties: AnalyticsProperties): AnalyticsProperties {
  const { quoteId, version, offerCount, code } = properties;
  return Object.fromEntries(
    Object.entries({ quoteId, version, offerCount, code }).filter(
      ([, value]) => value !== undefined,
    ),
  );
}
