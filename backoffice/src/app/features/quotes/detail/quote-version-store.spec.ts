import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { body } from '../../../../testing/fixtures';
import { byPath, provideTestApi, respond, settle } from '../../../../testing/http';
import type { QuoteVersion } from '../../../shared/api';
import { ANALYTICS_DOM_EVENT } from '../../../shared/state/analytics';
import { QuoteVersionStore } from './quote-version-store';

const QUOTE = '5b0b6c1e-5f1d-4a8e-9a39-2f1f5d6e7c11';
const VERSION_URL = `/v1/quotes/${QUOTE}/versions/1`;
const OFFERS_URL = `/v1/quotes/${QUOTE}/versions/1/offers`;

describe('QuoteVersionStore', () => {
  let store: QuoteVersionStore;
  let controller: HttpTestingController;

  beforeEach(() => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
    sessionStorage.setItem(
      'backoffice.auth',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 3_600_000 }),
    );
    TestBed.configureTestingModule({
      providers: [provideTestApi(), provideRouter([]), QuoteVersionStore],
    });
    store = TestBed.inject(QuoteVersionStore);
    controller = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    store.close();
    controller.verify();
  });

  async function openWithPartialOffer(): Promise<void> {
    store.open(QUOTE, 1);
    await settle();
    respond(
      controller.expectOne(byPath(VERSION_URL)),
      'quotePartialOffer',
      'oferta da alpha em QUOTING',
    );
    await settle();
    respond(controller.expectOne(byPath(OFFERS_URL)), 'quotePartialOffer', 'ofertas parciais');
    await settle();
  }

  it('mostra a oferta da alpha enquanto a beta segue em consulta (FT04)', async () => {
    const events: unknown[] = [];
    const listener = (event: Event) => events.push((event as CustomEvent).detail);
    window.addEventListener(ANALYTICS_DOM_EVENT, listener);

    await openWithPartialOffer();
    window.removeEventListener(ANALYTICS_DOM_EVENT, listener);

    expect(store.version()?.phase).toBe('QUOTING');
    expect(store.offers().length).toBeGreaterThan(0);
    expect(store.version()?.insurerResults.some((result) => result.status === 'PROCESSING')).toBe(
      true,
    );
    expect(events).toEqual([
      expect.objectContaining({ event: 'FirstOfferDisplayed', quoteId: QUOTE, version: 1 }),
    ]);
  });

  it('uma resposta com revision menor não regride a versão exibida (FT07)', async () => {
    await openWithPartialOffer();
    const shown = store.version()!;

    const refresh = store.refresh();
    await settle();
    const older = {
      ...body<QuoteVersion>('quoteProgress', 'recebida'),
      revision: shown.revision - 1,
    };
    controller.expectOne(byPath(VERSION_URL)).flush(older);
    await settle();
    respond(controller.expectOne(byPath(OFFERS_URL)), 'quotePartialOffer', 'ofertas parciais');
    await refresh;

    expect(store.version()).toEqual(shown);
  });

  it('numa falha de rede, mantém as ofertas recebidas e avisa a desatualização (FT06)', async () => {
    await openWithPartialOffer();
    const offers = store.offers();

    const refresh = store.refresh();
    await settle();
    controller.expectOne(byPath(VERSION_URL)).error(new ProgressEvent('error'), { status: 0 });
    await refresh;

    expect(store.offers()).toEqual(offers);
    expect(store.stale()).toBe(true);
    expect(store.refreshError()?.code).toBe('NETWORK_ERROR');
    expect(store.lastSuccessAt()).not.toBeNull();
  });

  it('para o polling no resultado final', async () => {
    store.open(QUOTE, 1);
    await settle();
    respond(controller.expectOne(byPath(VERSION_URL)), 'quoteDeclined', 'todas recusaram');
    await settle();

    expect(store.version()?.phase).toBe('TERMINAL');
    expect(store.offers()).toEqual([]);
  });

  it('sem dados, o 404 vira o erro de abertura e encerra a leitura', async () => {
    store.open(QUOTE, 1);
    await settle();
    respond(controller.expectOne(byPath(VERSION_URL)), 'errors', 'cotação de outra organização');
    await settle();

    expect(store.loadError()?.status).toBe(404);
    expect(store.version()).toBeNull();
  });
});
