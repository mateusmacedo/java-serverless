import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  CardFooterComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
} from '@coreui/angular';

import type { Offer, Selection } from '../../shared/api';
import { DateTimePipe, MoneyPipe } from '../../shared/ui/pipes';
import {
  installmentsText,
  looksExpired,
  NOT_INFORMED,
  offerAvailability,
} from './offer-presentation';

/**
 * Oferta de uma seguradora (PRD §8): prêmio total e moeda, parcelas, coberturas com importância segurada, franquias e
 * validade. Não destaca "melhor oferta". A seleção só aparece quando o servidor a permite para a oferta e a versão.
 */
@Component({
  selector: 'app-offer-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    CardFooterComponent,
    DateTimePipe,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    MoneyPipe,
  ],
  template: `
    <c-card [attr.aria-labelledby]="headingId()" class="h-100" role="region">
      <c-card-body>
        <div class="d-flex flex-wrap justify-content-between gap-2 mb-2">
          <h3 [id]="headingId()" class="h6 mb-0">{{ offer().insurerName }}</h3>
          <c-badge [color]="availability().color">{{ availability().label }}</c-badge>
        </div>
        <p class="mb-1">
          <span class="text-body-secondary small d-block">Prêmio total</span>
          <span class="fs-4 fw-semibold">{{ offer().premiumTotal | money: offer().currency }}</span>
        </p>
        <p class="small mb-2"><span class="text-body-secondary">Parcelamento:</span> {{ installments() }}</p>

        <p class="small text-body-secondary mb-1">Coberturas</p>
        <ul class="small ps-3 mb-2">
          @for (coverage of offer().coverages; track coverage.code) {
            <li>
              {{ coverage.label }}:
              {{ coverage.insuredAmount !== undefined ? (coverage.insuredAmount | money: offer().currency) : notInformed }}
            </li>
          } @empty {
            <li>{{ notInformed }}</li>
          }
        </ul>

        <p class="small text-body-secondary mb-1">Franquias</p>
        <ul class="small ps-3 mb-2">
          @for (deductible of offer().deductibles; track deductible.coverageCode) {
            <li>{{ coverageLabel(deductible.coverageCode) }}: {{ deductible.amount | money: offer().currency }}</li>
          } @empty {
            <li>{{ notInformed }}</li>
          }
        </ul>

        <p class="small mb-0">
          <span class="text-body-secondary">Válida até</span> {{ offer().validUntil | dateTime }}
          @if (expiredLocally() && offer().selectable) {
            <span class="d-block text-warning-emphasis">
              O prazo parece ter passado; a confirmação depende do servidor, que confere a validade ao selecionar.
            </span>
          }
        </p>
      </c-card-body>
      <c-card-footer class="d-flex flex-wrap align-items-center gap-2">
        <c-form-check class="me-auto mb-0">
          <input
            (change)="compareChange.emit(!compared())"
            [checked]="compared()"
            [disabled]="!compared() && compareLimitReached()"
            [id]="'compare-' + offer().id"
            cFormCheckInput
            type="checkbox"
          />
          <label [for]="'compare-' + offer().id" cFormCheckLabel>Comparar</label>
        </c-form-check>
        @if (canSelect()) {
          <button (click)="selectOffer.emit()" [disabled]="selectDisabled()" cButton color="primary" size="sm" type="button">
            Selecionar oferta<span class="visually-hidden"> da {{ offer().insurerName }}</span>
          </button>
        }
      </c-card-footer>
    </c-card>
  `,
})
export class OfferCardComponent {
  readonly offer = input.required<Offer>();
  readonly selection = input<Selection | null>(null);
  readonly clockOffsetMs = input(0);
  /** O servidor oferece `SELECT_OFFER` na versão e a sessão tem `offers:select`. */
  readonly selectionAllowed = input(false);
  readonly selectDisabled = input(false);
  readonly compared = input(false);
  readonly compareLimitReached = input(false);

  readonly compareChange = output<boolean>();
  readonly selectOffer = output<void>();

  readonly notInformed = NOT_INFORMED;
  readonly headingId = computed(() => `offer-${this.offer().id}-title`);
  readonly availability = computed(() => offerAvailability(this.offer(), this.selection()));
  readonly installments = computed(() =>
    installmentsText(this.offer().installments, this.offer().currency),
  );
  readonly expiredLocally = computed(() => looksExpired(this.offer(), this.clockOffsetMs()));
  readonly canSelect = computed(() => this.selectionAllowed() && this.offer().selectable);

  coverageLabel(code: string): string {
    return this.offer().coverages.find((coverage) => coverage.code === code)?.label ?? code;
  }
}
