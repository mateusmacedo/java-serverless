import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  RowComponent,
  TableDirective,
} from '@coreui/angular';
import { cilPlus } from '@coreui/icons';
import { IconDirective } from '@coreui/icons-angular';

import { SessionStore } from '../../../core/auth/session-store';
import { listQuotesResource, type Outcome, type Phase } from '../../../shared/api';
import { CursorPaginationComponent } from '../../../shared/ui/pagination/cursor-pagination.component';
import { DateTimePipe } from '../../../shared/ui/pipes';
import {
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
} from '../../../shared/ui/states/states';
import { toApiError } from '../../../shared/util/api-error';
import { OUTCOMES, PHASES, shortId } from '../quote-presentation';
import { QuoteStatusBadgeComponent } from '../ui/quote-status-badge.component';
import {
  dateRangeError,
  hasFilters,
  OUTCOME_OPTIONS,
  PHASE_OPTIONS,
  type QuoteListChanges,
  quoteListRequest,
  readQuoteListQuery,
} from './quote-list-query';

/**
 * Cotações da organização (`/quotes`): filtros de fase, resultado e período e o cursor na URL, com a primeira página
 * a cada filtro novo. A lista vazia não é erro: mostra a ação de criar.
 */
@Component({
  selector: 'app-quote-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    CursorPaginationComponent,
    DateTimePipe,
    EmptyStateComponent,
    ErrorStateComponent,
    FormControlDirective,
    FormLabelDirective,
    FormSelectDirective,
    IconDirective,
    LoadingStateComponent,
    QuoteStatusBadgeComponent,
    RouterLink,
    RowComponent,
    TableDirective,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <div class="d-flex flex-wrap gap-2 align-items-start mb-3">
          <div class="me-auto">
            <h1 class="h4 card-title mb-0">Cotações</h1>
            <div class="small text-body-secondary">As cotações da sua organização, das mais novas para as mais antigas.</div>
          </div>
          @if (canCreate()) {
            <a cButton color="primary" routerLink="new">
              <svg [cIcon]="plusIcon" aria-hidden="true" class="me-1"></svg>
              Nova cotação
            </a>
          }
        </div>

        <form (submit)="applyFilters($event)" aria-label="Filtros das cotações" class="mb-3">
          <c-row class="g-2 align-items-end">
            <c-col md="3" xs="12">
              <label cLabel for="quote-filter-phase">Fase</label>
              <select [value]="draft().phase ?? ''" (change)="setDraft('phase', $event)" cSelect id="quote-filter-phase">
                <option value="">Todas</option>
                @for (phase of phases; track phase) {
                  <option [selected]="phase === draft().phase" [value]="phase">{{ phaseLabel(phase) }}</option>
                }
              </select>
            </c-col>
            <c-col md="3" xs="12">
              <label cLabel for="quote-filter-outcome">Resultado</label>
              <select [value]="draft().outcome ?? ''" (change)="setDraft('outcome', $event)" cSelect id="quote-filter-outcome">
                <option value="">Todos</option>
                @for (outcome of outcomes; track outcome) {
                  <option [selected]="outcome === draft().outcome" [value]="outcome">{{ outcomeLabel(outcome) }}</option>
                }
              </select>
            </c-col>
            <c-col md="2" xs="6">
              <label cLabel for="quote-filter-from">Criada de</label>
              <input (change)="setDraft('createdFrom', $event)" [value]="draft().createdFrom ?? ''" cFormControl id="quote-filter-from" type="date" />
            </c-col>
            <c-col md="2" xs="6">
              <label cLabel for="quote-filter-to">Criada até</label>
              <input
                (change)="setDraft('createdTo', $event)"
                [attr.aria-describedby]="rangeError() ? 'quote-filter-range-error' : null"
                [attr.aria-invalid]="!!rangeError()"
                [valid]="rangeError() ? false : undefined"
                [value]="draft().createdTo ?? ''"
                cFormControl
                id="quote-filter-to"
                type="date"
              />
            </c-col>
            <c-col class="d-flex gap-2" md="2" xs="12">
              <button cButton color="secondary" type="submit" variant="outline">Filtrar</button>
              @if (filtered()) {
                <button (click)="clearFilters()" cButton color="link" type="button">Limpar</button>
              }
            </c-col>
          </c-row>
          @if (rangeError(); as message) {
            <div class="invalid-feedback d-block" id="quote-filter-range-error">{{ message }}</div>
          }
        </form>

        @if (rangeError()) {
          <app-empty-state message="Corrija o período para ver as cotações." />
        } @else if (loadError(); as error) {
          <app-error-state (retry)="quotes.reload()" [error]="error" message="Não foi possível carregar as cotações." />
        } @else if (!page()) {
          <app-loading-state label="Carregando cotações…" />
        } @else if (!items().length) {
          @if (filtered()) {
            <app-empty-state message="Nenhuma cotação encontrada com estes filtros.">
              <button (click)="clearFilters()" cButton color="link" type="button">Limpar filtros</button>
            </app-empty-state>
          } @else {
            <app-empty-state message="Nenhuma cotação ainda.">
              @if (canCreate()) {
                <a cButton color="primary" routerLink="new">Nova cotação</a>
              }
            </app-empty-state>
          }
        } @else {
          <div class="table-responsive">
            <table [hover]="true" cTable class="align-middle mb-0">
              <caption class="visually-hidden">Cotações da organização</caption>
              <thead>
                <tr>
                  <th scope="col">Protocolo</th>
                  <th scope="col">Criada em</th>
                  <th scope="col">Versão</th>
                  <th scope="col">Situação</th>
                  <th class="text-end" scope="col">Ofertas</th>
                </tr>
              </thead>
              <tbody>
                @for (quote of items(); track quote.id) {
                  <tr>
                    <td>
                      <a [routerLink]="[quote.id, 'versions', quote.currentVersion]" [title]="quote.id">
                        <span class="visually-hidden">Abrir a cotação </span>{{ shortId(quote.id) }}
                      </a>
                    </td>
                    <td class="text-nowrap">{{ quote.createdAt | dateTime }}</td>
                    <td>{{ quote.currentVersion }}</td>
                    <td><app-quote-status-badge [outcome]="quote.outcome ?? null" [phase]="quote.phase" /></td>
                    <td class="text-end">{{ quote.offerCount }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (!loadError() && !rangeError()) {
          <app-cursor-pagination
            (cursorChange)="navigate({ cursor: $event ?? null })"
            (limitChange)="navigate({ limit: $event, cursor: null })"
            [cursor]="query().cursor"
            [disabled]="quotes.isLoading()"
            [limit]="query().limit"
            [nextCursor]="page()?.nextCursor"
          />
        }
      </c-card-body>
    </c-card>
  `,
})
export class QuoteListPageComponent {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #session = inject(SessionStore);

  readonly #params = toSignal(this.#route.queryParamMap, { requireSync: true });
  readonly query = computed(() => readQuoteListQuery(this.#params()));
  readonly draft = linkedSignal(() => this.query());
  readonly rangeError = computed(
    () => dateRangeError(this.draft()) ?? dateRangeError(this.query()),
  );
  readonly filtered = computed(() => hasFilters(this.query()));
  readonly canCreate = computed(() => this.#session.can('quotes:write'));

  readonly quotes = listQuotesResource(() =>
    dateRangeError(this.query()) ? undefined : { query: quoteListRequest(this.query()) },
  );
  readonly page = computed(() => (this.quotes.hasValue() ? this.quotes.value() : undefined));
  readonly items = computed(() => this.page()?.items ?? []);
  readonly loadError = computed(() => {
    const error = this.quotes.error();
    return error ? toApiError(error) : null;
  });

  readonly phases = PHASE_OPTIONS;
  readonly outcomes = OUTCOME_OPTIONS;
  readonly plusIcon = cilPlus;
  readonly shortId = shortId;

  phaseLabel(phase: Phase): string {
    return PHASES[phase].label;
  }

  outcomeLabel(outcome: Outcome): string {
    return OUTCOMES[outcome].label;
  }

  setDraft(field: 'phase' | 'outcome' | 'createdFrom' | 'createdTo', event: Event): void {
    const value = (event.target as HTMLInputElement | HTMLSelectElement).value || null;
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  /** Leva os filtros à URL e volta à primeira página; um período invertido fica só no aviso. */
  applyFilters(event: Event): void {
    event.preventDefault();
    const draft = this.draft();
    if (dateRangeError(draft)) {
      return;
    }
    this.navigate({
      phase: draft.phase,
      outcome: draft.outcome,
      createdFrom: draft.createdFrom,
      createdTo: draft.createdTo,
      cursor: null,
    });
  }

  clearFilters(): void {
    this.navigate({ phase: null, outcome: null, createdFrom: null, createdTo: null, cursor: null });
  }

  navigate(changes: QuoteListChanges): void {
    void this.#router.navigate([], {
      relativeTo: this.#route,
      queryParams: changes,
      queryParamsHandling: 'merge',
    });
  }
}
