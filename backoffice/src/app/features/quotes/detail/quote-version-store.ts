import { computed, DestroyRef, DOCUMENT, inject, Injectable, signal } from '@angular/core';

import { APP_CONFIG } from '../../../core/config/app-config';
import {
  getQuoteVersion,
  listQuoteOffers,
  type Offer,
  type OffersPage,
  type QuoteVersion,
} from '../../../shared/api';
import { Analytics } from '../../../shared/state/analytics';
import { type PollOutcome, Poller } from '../../../shared/state/poller';
import { type ApiError, apiData, toApiError } from '../../../shared/util/api-error';
import { toMillis } from '../../../shared/util/dates';

/** Páginas de ofertas lidas por rodada; o MVP tem poucas seguradoras, e o teto evita um laço sem fim. */
const MAX_OFFER_PAGES = 10;

interface VersionKey {
  readonly quoteId: string;
  readonly version: number;
}

/**
 * Estado de servidor de uma versão da cotação e das ofertas dela, separado do estado da tela. A leitura segue o
 * contrato compartilhado: polling sem sobreposição no ritmo do `retryAfterSeconds`, suspenso com a aba oculta e
 * encerrado em `TERMINAL`; uma resposta com `revision` menor não substitui a exibida (a da versão e a das ofertas não
 * se comparam); numa falha, os dados recebidos continuam na tela com o aviso de desatualização.
 */
@Injectable()
export class QuoteVersionStore {
  readonly #config = inject(APP_CONFIG);
  readonly #document = inject(DOCUMENT);
  readonly #analytics = inject(Analytics);

  readonly #version = signal<QuoteVersion | null>(null);
  readonly #offers = signal<OffersPage | null>(null);
  readonly #loadError = signal<ApiError | null>(null);
  readonly #refreshError = signal<ApiError | null>(null);
  readonly #lastSuccessAt = signal<Date | null>(null);
  readonly #clockOffsetMs = signal(0);
  readonly #refreshing = signal(false);
  #key: VersionKey | null = null;
  #poller: Poller | null = null;

  readonly version = this.#version.asReadonly();
  readonly offers = computed<readonly Offer[]>(() => this.#offers()?.items ?? []);
  /** Erro da primeira leitura, sem dados para mostrar. */
  readonly loadError = this.#loadError.asReadonly();
  /** Erro da última atualização, com os dados anteriores ainda na tela. */
  readonly refreshError = this.#refreshError.asReadonly();
  readonly lastSuccessAt = this.#lastSuccessAt.asReadonly();
  readonly stale = computed(() => this.#refreshError() !== null && this.#version() !== null);
  readonly refreshing = this.#refreshing.asReadonly();
  /** Diferença entre o relógio do servidor e o do navegador, para estimar a validade das ofertas. */
  readonly clockOffsetMs = this.#clockOffsetMs.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.close());
  }

  /** Abre a versão e começa o polling; trocar de versão descarta o estado da anterior. */
  open(quoteId: string, version: number): void {
    if (this.#key?.quoteId === quoteId && this.#key.version === version) {
      return;
    }
    this.close();
    this.#key = { quoteId, version };
    this.#version.set(null);
    this.#offers.set(null);
    this.#loadError.set(null);
    this.#refreshError.set(null);
    this.#lastSuccessAt.set(null);
    this.#poller = new Poller({
      tick: () => this.#tick(),
      retrySeconds: this.#config.pollIntervalSeconds,
      document: this.#document,
    });
    void this.#poller.start();
  }

  /** "Atualizar" e leitura antes da seleção: espera a rodada em andamento em vez de abrir outra. */
  async refresh(): Promise<void> {
    if (!this.#poller) {
      return;
    }
    this.#refreshing.set(true);
    try {
      await this.#poller.refresh();
    } finally {
      this.#refreshing.set(false);
    }
  }

  close(): void {
    this.#poller?.stop();
    this.#poller = null;
    this.#key = null;
  }

  async #tick(): Promise<PollOutcome> {
    const key = this.#key;
    if (!key) {
      return null;
    }
    try {
      const version = await apiData(
        getQuoteVersion({ path: { quoteId: key.quoteId, version: key.version } }),
      );
      if (key !== this.#key) {
        return null;
      }
      this.#applyVersion(version);
      // NOTE: decide pela versão exibida, que uma resposta antiga não substituiu.
      const current = this.#version() ?? version;
      if (needsOffers(current)) {
        const offers = await this.#loadOffers(key);
        if (key !== this.#key) {
          return null;
        }
        this.#applyOffers(offers);
      }
      this.#loadError.set(null);
      this.#refreshError.set(null);
      this.#lastSuccessAt.set(new Date());
      return current.phase === 'TERMINAL'
        ? null
        : (current.retryAfterSeconds ?? this.#config.pollIntervalSeconds);
    } catch (error_) {
      if (key !== this.#key) {
        return null;
      }
      const error = toApiError(error_);
      if (this.#version() === null) {
        this.#loadError.set(error);
      } else {
        this.#refreshError.set(error);
      }
      // NOTE: recurso inacessível ou sem permissão não melhora com novas leituras; a tela oferece o "Tentar de novo".
      if (error.status === 403 || error.status === 404) {
        return null;
      }
      throw error;
    }
  }

  #applyVersion(next: QuoteVersion): void {
    const current = this.#version();
    if (current && next.revision < current.revision) {
      return;
    }
    this.#version.set(next);
    this.#setClockOffset(next.serverTime);
  }

  #applyOffers(next: OffersPage): void {
    const current = this.#offers();
    if (current && next.revision < current.revision) {
      return;
    }
    this.#offers.set(next);
    this.#setClockOffset(next.serverTime);
    const key = this.#key;
    if (key && next.items.length) {
      this.#analytics.trackOnce(
        `first-offer:${key.quoteId}:${key.version}`,
        'FirstOfferDisplayed',
        {
          quoteId: key.quoteId,
          version: key.version,
          offerCount: next.items.length,
        },
      );
    }
  }

  async #loadOffers(key: VersionKey): Promise<OffersPage> {
    const path = { quoteId: key.quoteId, version: key.version };
    const first = await apiData(listQuoteOffers({ path, query: { limit: 100 } }));
    const items = [...first.items];
    const appendPages = async (cursor: string | null, page: number): Promise<void> => {
      if (!cursor || page >= MAX_OFFER_PAGES) {
        return;
      }
      const next = await apiData(listQuoteOffers({ path, query: { limit: 100, cursor } }));
      items.push(...next.items);
      await appendPages(next.nextCursor ?? null, page + 1);
    };
    await appendPages(first.nextCursor ?? null, 1);
    return { ...first, items, nextCursor: null };
  }

  #setClockOffset(serverTime: string): void {
    const server = toMillis(serverTime);
    if (server !== undefined) {
      this.#clockOffsetMs.set(server - Date.now());
    }
  }
}

/** Há ofertas para ler quando alguma seguradora ofertou ou a versão já tem seleção. */
function needsOffers(version: QuoteVersion): boolean {
  return (
    version.selection !== null ||
    version.insurerResults.some((result) => result.status === 'QUOTED')
  );
}
