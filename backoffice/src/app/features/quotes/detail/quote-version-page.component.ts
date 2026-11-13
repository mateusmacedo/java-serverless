import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DOCUMENT,
  effect,
  inject,
  input,
  numberAttribute,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AlertComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  RowComponent,
  SpinnerComponent,
} from '@coreui/angular';

import { SessionStore } from '../../../core/auth/session-store';
import {
  type Offer,
  type Selection,
  type SelectionRequest,
  selectOffer,
} from '../../../shared/api';
import { Analytics } from '../../../shared/state/analytics';
import { IdempotentMutation } from '../../../shared/state/idempotent-mutation';
import { SessionScopedStorage } from '../../../shared/state/session-storage';
import { DateTimePipe, MoneyPipe } from '../../../shared/ui/pipes';
import {
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
} from '../../../shared/ui/states/states';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { apiData, errorMessage } from '../../../shared/util/api-error';
import { formatTime } from '../../../shared/util/dates';
import { OfferCardComponent } from '../../offers/offer-card.component';
import { MAX_COMPARED_OFFERS, sortByPremium } from '../../offers/offer-presentation';
import {
  SelectionDialogComponent,
  type SelectionDialogStep,
} from '../../offers/selection-dialog.component';
import {
  isInsurerActive,
  OUTCOME_REASONS,
  OUTCOMES,
  pendingFields,
  PHASES,
  shortId,
  UNAVAILABILITY_REASONS,
} from '../quote-presentation';
import { QuoteStatusBadgeComponent } from '../ui/quote-status-badge.component';
import { RecoveryNoticeComponent } from '../ui/recovery-notice.component';
import { QuoteVersionStore } from './quote-version-store';
import {
  InsurerResultListComponent,
  MissingInformationPanelComponent,
  PhaseTimelineComponent,
  QuoteSummaryComponent,
  VersionBannerComponent,
} from './version-parts';

/**
 * Acompanhamento de uma versão da cotação (PRD §4, §7 e §8): fases sem porcentagem, resultado por seguradora,
 * ofertas parciais enquanto outras consultas seguem, pendências, comparação e seleção. A página atualiza sozinha até o
 * fim das consultas, e uma falha de rede mantém os dados recebidos com o horário e o aviso.
 */
@Component({
  selector: 'app-quote-version-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [QuoteVersionStore],
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    DateTimePipe,
    EmptyStateComponent,
    ErrorStateComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    InsurerResultListComponent,
    LoadingStateComponent,
    MissingInformationPanelComponent,
    MoneyPipe,
    OfferCardComponent,
    PhaseTimelineComponent,
    QuoteStatusBadgeComponent,
    QuoteSummaryComponent,
    RecoveryNoticeComponent,
    RowComponent,
    SelectionDialogComponent,
    SpinnerComponent,
    VersionBannerComponent,
  ],
  template: `
    @if (store.loadError(); as error) {
      <c-card class="mb-4">
        <c-card-body>
          <app-error-state (retry)="store.refresh()" [error]="error" message="Não foi possível abrir a cotação." />
        </c-card-body>
      </c-card>
    } @else if (data(); as version) {
      <c-card class="mb-4">
        <c-card-body>
          <div class="d-flex flex-wrap gap-2 align-items-start mb-3">
            <div class="me-auto">
              <h1 class="h4 card-title mb-1">
                Cotação <span [title]="version.quoteId">{{ shortId(version.quoteId) }}</span>
                <small class="text-body-secondary fs-6">versão {{ version.version }}</small>
              </h1>
              <app-quote-status-badge [outcome]="version.outcome ?? null" [phase]="version.phase" />
            </div>
            <div class="text-end">
              <button (click)="store.refresh()" [disabled]="store.refreshing()" cButton color="secondary" size="sm" type="button" variant="outline">
                @if (store.refreshing()) {
                  <c-spinner aria-hidden="true" class="me-1" label="" size="sm" />
                }
                Atualizar
              </button>
              @if (store.lastSuccessAt(); as at) {
                <div class="small text-body-secondary mt-1">Atualizado às {{ time(at) }}</div>
              }
            </div>
          </div>

          @if (store.stale()) {
            <c-alert color="warning" role="alert">
              Não foi possível atualizar agora. {{ refreshErrorText() }} Mostrando os dados recebidos às
              {{ time(store.lastSuccessAt()) }}; a cotação não foi alterada.
              <button (click)="store.refresh()" cButton class="p-0 ms-1 align-baseline" color="link" type="button">Atualizar</button>
            </c-alert>
          }

          <app-version-banner [version]="version" />

          @if (selectionMutation().state() === 'unknown' && !dialogOpen()) {
            <app-recovery-notice
              (retry)="recoverSelection()"
              actionLabel="Verificar seleção"
              message="Uma seleção enviada nesta aba ficou sem confirmação. Ela pode ter sido registrada; verifique enviando de novo a mesma seleção, que não será duplicada."
            />
          }

          @if (version.selection; as selection) {
            <c-alert color="success" role="status">
              <p class="fw-semibold mb-1">Intenção de proposta registrada</p>
              <p class="mb-0">
                Registrada em {{ selection.selectedAt | dateTime }}
                @if (selectedOffer(); as offer) {
                  para a oferta da {{ offer.insurerName }} ({{ offer.premiumTotal | money: offer.currency }})
                }. Nenhuma proposta foi enviada à seguradora, e o seguro não está contratado.
              </p>
            </c-alert>
          }

          <h2 class="h6">Andamento</h2>
          <app-phase-timeline [version]="version" />
          <div aria-live="polite" class="mb-3">
            <p class="mb-1">{{ statusText() }}</p>
            @if (reasonText(); as reason) {
              <p class="mb-1 text-body-secondary">{{ reason }}</p>
            }
            @if (moreResultsMayArrive()) {
              <p class="mb-0 small text-body-secondary">Mais resultados podem chegar enquanto as outras seguradoras respondem.</p>
            }
          </div>

          <app-missing-information-panel
            [canComplement]="canComplement()"
            [pending]="pending()"
            [selected]="version.selection !== null"
          />
        </c-card-body>
      </c-card>

      <c-row class="g-4 mb-4">
        <c-col lg="8" xs="12">
          <c-card>
            <c-card-body>
              <div class="d-flex flex-wrap gap-2 align-items-center mb-3">
                <h2 class="h5 mb-0 me-auto">Ofertas <span class="text-body-secondary fs-6">({{ store.offers().length }})</span></h2>
                @if (store.offers().length > 1) {
                  <c-form-check [switch]="true" class="mb-0">
                    <input (change)="sortByPrice.set(!sortByPrice())" [checked]="sortByPrice()" cFormCheckInput id="sort-by-price" type="checkbox" />
                    <label cFormCheckLabel for="sort-by-price">Ordenar por prêmio total</label>
                  </c-form-check>
                }
                <button (click)="openComparison()" [disabled]="compared().length < 2" cButton color="secondary" size="sm" type="button" variant="outline">
                  Comparar selecionadas ({{ compared().length }}/{{ maxCompared }})
                </button>
              </div>
              @if (offers().length) {
                <c-row class="g-3">
                  @for (offer of offers(); track offer.id) {
                    <c-col md="6" xs="12">
                      <app-offer-card
                        (compareChange)="toggleCompare(offer.id, $event)"
                        (selectOffer)="openSelection(offer)"
                        [clockOffsetMs]="store.clockOffsetMs()"
                        [compareLimitReached]="compared().length >= maxCompared"
                        [compared]="compared().includes(offer.id)"
                        [offer]="offer"
                        [selectDisabled]="selectionMutation().locked() || selectionMutation().state() === 'unknown'"
                        [selection]="version.selection ?? null"
                        [selectionAllowed]="selectionAllowed()"
                      />
                    </c-col>
                  }
                </c-row>
              } @else if (version.phase === 'TERMINAL') {
                <app-empty-state message="Nenhuma oferta nesta versão." />
              } @else {
                <app-empty-state message="Nenhuma oferta ainda. As ofertas aparecem aqui assim que as seguradoras responderem." />
              }
            </c-card-body>
          </c-card>
        </c-col>
        <c-col lg="4" xs="12">
          <c-card class="mb-4">
            <c-card-body>
              <h2 class="h5">Seguradoras</h2>
              <app-insurer-result-list [results]="version.insurerResults" />
            </c-card-body>
          </c-card>
          <c-card>
            <c-card-body>
              <h2 class="h5">Dados da cotação</h2>
              <app-quote-summary [version]="version" />
            </c-card-body>
          </c-card>
        </c-col>
      </c-row>

      <app-selection-dialog
        (cancelled)="closeSelection()"
        (confirm)="confirmSelection()"
        (recover)="recoverSelection()"
        (visibleChange)="!$event && closeSelection()"
        [busy]="selectionMutation().state() === 'sending'"
        [error]="selectionMutation().error()"
        [offer]="dialogOffer()"
        [returnFocus]="returnFocus()"
        [step]="dialogStep()"
        [unavailableMessage]="unavailableMessage()"
        [unknown]="selectionMutation().state() === 'unknown'"
        [visible]="dialogOpen()"
      />
    } @else {
      <c-card class="mb-4">
        <c-card-body>
          <app-loading-state label="Carregando a cotação…" />
        </c-card-body>
      </c-card>
    }
  `,
})
export class QuoteVersionPageComponent {
  readonly store = inject(QuoteVersionStore);
  readonly #session = inject(SessionStore);
  readonly #storage = inject(SessionScopedStorage);
  readonly #toast = inject(ToastService);
  readonly #analytics = inject(Analytics);
  readonly #router = inject(Router);
  readonly #route = inject(ActivatedRoute);
  readonly #document = inject(DOCUMENT);

  readonly quoteId = input.required<string>();
  readonly version = input.required({ transform: numberAttribute });

  readonly data = this.store.version;
  readonly sortByPrice = signal(false);
  readonly compared = signal<readonly string[]>([]);
  readonly maxCompared = MAX_COMPARED_OFFERS;
  readonly shortId = shortId;

  readonly offers = computed(() =>
    this.sortByPrice() ? sortByPremium(this.store.offers()) : this.store.offers(),
  );
  readonly pending = computed(() => {
    const version = this.data();
    return version ? pendingFields(version) : [];
  });
  readonly #isCurrent = computed(() => {
    const version = this.data();
    return !!version && version.version === version.currentVersion && version.supersededAt === null;
  });
  readonly canComplement = computed(
    () =>
      this.#isCurrent() &&
      !!this.data()?.allowedActions.includes('CREATE_VERSION') &&
      this.#session.can('quotes:write'),
  );
  readonly selectionAllowed = computed(
    () =>
      !!this.data()?.allowedActions.includes('SELECT_OFFER') && this.#session.can('offers:select'),
  );
  readonly moreResultsMayArrive = computed(() => {
    const version = this.data();
    return (
      !!version &&
      version.phase !== 'TERMINAL' &&
      this.store.offers().length > 0 &&
      version.insurerResults.some((result) => isInsurerActive(result.status))
    );
  });
  readonly statusText = computed(() => {
    const version = this.data();
    if (!version) {
      return '';
    }
    return version.phase === 'TERMINAL' && version.outcome
      ? OUTCOMES[version.outcome].description
      : PHASES[version.phase].description;
  });
  readonly reasonText = computed(() => {
    const reason = this.data()?.outcomeReason;
    return reason ? OUTCOME_REASONS[reason] : null;
  });
  readonly selectedOffer = computed(() => {
    const selection = this.data()?.selection;
    return selection
      ? (this.store.offers().find((offer) => offer.id === selection.offerId) ?? null)
      : null;
  });
  readonly refreshErrorText = computed(() => errorMessage(this.store.refreshError()));

  /** Seleção da versão aberta, com a tentativa guardada na aba para recuperar depois de recarregar. */
  readonly selectionMutation = computed(() => {
    const quoteId = this.quoteId();
    const version = this.version();
    return untracked(
      () =>
        new IdempotentMutation<SelectionRequest, Selection>({
          send: (key, body) =>
            apiData(
              selectOffer({
                path: { quoteId, version },
                body,
                headers: { 'Idempotency-Key': key },
              }),
            ),
          store: this.#storage.attemptStore<SelectionRequest>(`select-offer.${quoteId}.${version}`),
        }),
    );
  });

  readonly dialogOpen = signal(false);
  readonly dialogStep = signal<SelectionDialogStep>('refreshing');
  readonly #dialogOfferId = signal<string | null>(null);
  readonly returnFocus = signal<HTMLElement | null>(null);
  readonly dialogOffer = computed(
    () => this.store.offers().find((offer) => offer.id === this.#dialogOfferId()) ?? null,
  );
  readonly unavailableMessage = computed(() => {
    const reason = this.dialogOffer()?.unavailabilityReason;
    return reason
      ? `${UNAVAILABILITY_REASONS[reason]}: a oferta não pode mais ser selecionada.`
      : 'A seleção não está mais disponível nesta versão.';
  });

  constructor() {
    effect(() => {
      const quoteId = this.quoteId();
      const version = this.version();
      untracked(() => {
        this.compared.set([]);
        this.store.open(quoteId, version);
      });
    });
  }

  time(at: Date | null): string {
    return formatTime(at, true);
  }

  toggleCompare(offerId: string, checked: boolean): void {
    this.compared.update((ids) => {
      if (!checked) {
        return ids.filter((id) => id !== offerId);
      }

      if (ids.includes(offerId) || ids.length >= MAX_COMPARED_OFFERS) {
        return ids;
      }

      return [...ids, offerId];
    });
  }

  openComparison(): void {
    void this.#router.navigate(['compare'], {
      relativeTo: this.#route,
      queryParams: { offers: this.compared().join(',') },
    });
  }

  /** Abre a confirmação lendo a versão e as ofertas de novo: a oferta pode ter expirado ou a versão mudado. */
  async openSelection(offer: Offer): Promise<void> {
    this.selectionMutation().reset();
    this.#dialogOfferId.set(offer.id);
    // NOTE: depois do sucesso, o botão que abriu o modal some com a oferta selecionada; o foco vai ao comparar dela.
    this.returnFocus.set(this.#document.getElementById(`compare-${offer.id}`));
    this.dialogStep.set('refreshing');
    this.dialogOpen.set(true);
    await this.store.refresh();
    this.#evaluateDialog();
  }

  async confirmSelection(): Promise<void> {
    const offerId = this.#dialogOfferId();
    if (offerId && this.dialogStep() === 'ready') {
      this.#settle(await this.selectionMutation().submit({ offerId }));
    }
  }

  async recoverSelection(): Promise<void> {
    this.#analytics.track('UiRecoveryRequested', { code: 'SELECT_OFFER', quoteId: this.quoteId() });
    const attempt = this.selectionMutation().attempt();
    if (attempt) {
      this.#dialogOfferId.set(attempt.body.offerId);
    }
    this.#settle(await this.selectionMutation().retry());
  }

  closeSelection(): void {
    if (this.selectionMutation().state() !== 'sending') {
      this.dialogOpen.set(false);
    }
  }

  #settle(selection: Selection | null): void {
    const mutation = this.selectionMutation();
    if (selection) {
      this.#analytics.track('OfferSelectionConfirmed', {
        quoteId: this.quoteId(),
        version: this.version(),
      });
      this.#toast.success(
        'Intenção de proposta registrada. Nenhuma proposta foi enviada à seguradora.',
      );
      this.dialogOpen.set(false);
      void this.store.refresh();
      return;
    }
    if (mutation.state() === 'rejected' && mutation.error()?.status === 409) {
      void this.store.refresh().then(() => {
        if (this.dialogStep() === 'ready') {
          this.dialogStep.set('unavailable');
        }
      });
    }
  }

  #evaluateDialog(): void {
    const offer = this.dialogOffer();
    const version = this.data();
    const available =
      !!offer && offer.selectable && !!version?.allowedActions.includes('SELECT_OFFER');
    this.dialogStep.set(available ? 'ready' : 'unavailable');
  }
}
