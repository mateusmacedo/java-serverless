import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  numberAttribute,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AlertComponent,
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  TableDirective,
} from '@coreui/angular';

import { Analytics } from '../../shared/state/analytics';
import { ErrorStateComponent, LoadingStateComponent } from '../../shared/ui/states/states';
import { QuoteVersionStore } from '../quotes/detail/quote-version-store';
import { shortId } from '../quotes/quote-presentation';
import { buildComparison } from './offer-comparison';
import { MAX_COMPARED_OFFERS, offerAvailability } from './offer-presentation';

/**
 * Comparação de até três ofertas da versão (`?offers=<id>,<id>`), pelos códigos normalizados de cobertura: prêmio,
 * parcelas, validade, importâncias seguradas e franquias, com as diferenças de limite e franquia marcadas em texto.
 * Em tela estreita, cada oferta vira um cartão com as mesmas linhas.
 */
@Component({
  selector: 'app-offer-comparison-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [QuoteVersionStore],
  imports: [
    AlertComponent,
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    RouterLink,
    TableDirective,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <div class="d-flex flex-wrap gap-2 align-items-start mb-3">
          <div class="me-auto">
            <h1 class="h4 card-title mb-0">Comparação de ofertas</h1>
            <div class="small text-body-secondary">
              Cotação {{ shortId(quoteId()) }}, versão {{ version() }}. Nenhuma oferta é destacada como a melhor.
            </div>
          </div>
          <a [routerLink]="['..']" cButton color="secondary" variant="outline">Voltar à cotação</a>
        </div>

        @if (store.loadError(); as error) {
          <app-error-state (retry)="store.refresh()" [error]="error" message="Não foi possível carregar as ofertas." />
        } @else if (!store.version()) {
          <app-loading-state label="Carregando as ofertas…" />
        } @else if (comparedOffers().length < 2) {
          <c-alert color="info">
            Escolha pelo menos duas ofertas da cotação para comparar.
            <a [routerLink]="['..']">Voltar à cotação</a>
          </c-alert>
        } @else {
          @if (missing()) {
            <c-alert color="warning" role="status">Algumas ofertas escolhidas não estão mais nesta versão.</c-alert>
          }
          <p class="small text-body-secondary">
            “Não incluída” é a cobertura que a oferta não tem; “Não informado” é o dado que a seguradora não enviou. Nenhum
            dos dois é zero. As linhas marcadas com “Diferente” têm limite ou franquia diferente entre as ofertas.
          </p>

          <div class="table-responsive d-none d-md-block">
            <table cTable class="comparison-table align-middle">
              <caption class="visually-hidden">Comparação das ofertas escolhidas</caption>
              <thead>
                <tr>
                  <th scope="col">Item</th>
                  @for (offer of comparedOffers(); track offer.id) {
                    <th scope="col">
                      {{ offer.insurerName }}
                      <c-badge [color]="availability(offer.id).color" class="d-block mt-1 text-wrap">{{ availability(offer.id).label }}</c-badge>
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row.key) {
                  <tr [class.divergent]="row.divergent">
                    <th scope="row">
                      {{ row.label }}
                      @if (row.divergent) {
                        <c-badge class="ms-1" color="warning" textColor="dark">Diferente</c-badge>
                      }
                    </th>
                    @for (cell of row.cells; track $index) {
                      <td [class.text-body-secondary]="cell.kind !== 'value'" [class.fst-italic]="cell.kind !== 'value'">{{ cell.text }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>

          <div class="d-md-none">
            @for (offer of comparedOffers(); track offer.id; let column = $index) {
              <section [attr.aria-labelledby]="'compare-card-' + offer.id" class="border rounded p-3 mb-3">
                <h2 [id]="'compare-card-' + offer.id" class="h6">{{ offer.insurerName }}</h2>
                <c-badge [color]="availability(offer.id).color" class="mb-2">{{ availability(offer.id).label }}</c-badge>
                <dl class="mb-0 small">
                  @for (row of rows(); track row.key) {
                    <dt>
                      {{ row.label }}
                      @if (row.divergent) {
                        <c-badge class="ms-1" color="warning" textColor="dark">Diferente</c-badge>
                      }
                    </dt>
                    <dd [class.fst-italic]="row.cells[column].kind !== 'value'">{{ row.cells[column].text }}</dd>
                  }
                </dl>
              </section>
            }
          </div>
        }
      </c-card-body>
    </c-card>
  `,
})
export class OfferComparisonPageComponent {
  readonly store = inject(QuoteVersionStore);
  readonly #analytics = inject(Analytics);

  readonly quoteId = input.required<string>();
  readonly version = input.required({ transform: numberAttribute });
  /** Ids das ofertas na URL (`?offers=`), separados por vírgula; vale o máximo de três. */
  readonly offers = input<string | undefined>();

  readonly shortId = shortId;
  readonly #ids = computed(() =>
    (this.offers() ?? '')
      .split(',')
      .map((id) => id.trim())
      .filter(Boolean)
      .slice(0, MAX_COMPARED_OFFERS),
  );
  readonly comparedOffers = computed(() => {
    const ids = this.#ids();
    const offers = this.store.offers();
    return ids.flatMap((id) => offers.filter((offer) => offer.id === id));
  });
  readonly missing = computed(() => this.comparedOffers().length < this.#ids().length);
  readonly rows = computed(() => buildComparison(this.comparedOffers()));

  constructor() {
    effect(() => {
      const quoteId = this.quoteId();
      const version = this.version();
      untracked(() => this.store.open(quoteId, version));
    });
    effect(() => {
      const count = this.comparedOffers().length;
      if (count >= 2) {
        untracked(() =>
          this.#analytics.trackOnce(
            `comparison:${this.quoteId()}:${this.version()}:${this.#ids().join(',')}`,
            'OfferComparisonOpened',
            { quoteId: this.quoteId(), version: this.version(), offerCount: count },
          ),
        );
      }
    });
  }

  availability(offerId: string) {
    const offer = this.comparedOffers().find((item) => item.id === offerId);
    return offer
      ? offerAvailability(offer, this.store.version()?.selection)
      : { label: '', color: 'secondary' as const };
  }
}
