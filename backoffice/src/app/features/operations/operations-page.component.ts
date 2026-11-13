import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  BadgeComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  ColComponent,
  FormLabelDirective,
  FormSelectDirective,
  ListGroupDirective,
  ListGroupItemDirective,
  RowComponent,
  TableDirective,
} from '@coreui/angular';

import { SessionStore } from '../../core/auth/session-store';
import {
  type Issue,
  type IssueStatus,
  type IssueType,
  listOperationIssuesResource,
  type ReconcileAccepted,
  type ReconcileRequest,
  reconcileQuery,
} from '../../shared/api';
import { IdempotentMutation } from '../../shared/state/idempotent-mutation';
import { SessionScopedStorage } from '../../shared/state/session-storage';
import { CursorPaginationComponent } from '../../shared/ui/pagination/cursor-pagination.component';
import { DateTimePipe, RelativeTimePipe } from '../../shared/ui/pipes';
import {
  EmptyStateComponent,
  ErrorStateComponent,
  LoadingStateComponent,
} from '../../shared/ui/states/states';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { apiData, errorMessage, toApiError } from '../../shared/util/api-error';
import { shortId } from '../quotes/quote-presentation';
import { OperationTracker } from './operation-tracker';
import {
  ISSUE_STATUS_OPTIONS,
  ISSUE_STATUSES,
  ISSUE_TYPE_OPTIONS,
  ISSUE_TYPES,
  issueListRequest,
  isOperationFinished,
  OPERATION_STATUSES,
  readIssueListQuery,
} from './operations-presentation';
import { ReconciliationDialogComponent } from './reconciliation-dialog.component';

/**
 * Painel operacional (`/operations`, PRD §10 e FE11): ocorrências com tipo, idade, resumo seguro e a cotação, sem
 * payload de fornecedor. A reconciliação só aparece com a permissão e quando o servidor a oferece na ocorrência; o
 * acompanhamento lê a operação até o fim, sem repetir o comando.
 */
@Component({
  selector: 'app-operations-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OperationTracker],
  imports: [
    BadgeComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ColComponent,
    CursorPaginationComponent,
    DateTimePipe,
    EmptyStateComponent,
    ErrorStateComponent,
    FormLabelDirective,
    FormSelectDirective,
    ListGroupDirective,
    ListGroupItemDirective,
    LoadingStateComponent,
    ReconciliationDialogComponent,
    RelativeTimePipe,
    RouterLink,
    RowComponent,
    TableDirective,
  ],
  template: `
    @if (tracker.tracked().length) {
      <c-card class="mb-4">
        <c-card-body>
          <h2 class="h5">Reconciliações pedidas nesta sessão</h2>
          <ul aria-live="polite" cListGroup>
            @for (item of tracker.tracked(); track item.operationId) {
              <li cListGroupItem class="d-flex flex-wrap gap-2 align-items-center">
                <span class="me-auto">Operação <code>{{ shortId(item.operationId) }}</code></span>
                @if (item.operation; as operation) {
                  <c-badge [color]="operationStatus(operation.status).color">{{ operationStatus(operation.status).label }}</c-badge>
                  <span class="w-100 small">{{ operation.safeSummary }}</span>
                  <span class="w-100 small text-body-secondary">Atualizada {{ operation.updatedAt | relativeTime }}</span>
                  @if (finished(item.operation)) {
                    <button (click)="tracker.dismiss(item.operationId)" cButton class="p-0" color="link" size="sm" type="button">Remover da lista</button>
                  }
                } @else if (!item.error) {
                  <c-badge color="secondary">Consultando…</c-badge>
                }
                @if (item.error; as error) {
                  <span class="w-100 small text-danger">
                    Não foi possível consultar a situação. {{ errorText(error) }}
                    @if (error.correlationId) {
                      Código para o suporte: <code>{{ error.correlationId }}</code>.
                    }
                  </span>
                  <button (click)="tracker.refresh(item.operationId)" cButton class="p-0" color="link" size="sm" type="button">Atualizar</button>
                }
              </li>
            }
          </ul>
        </c-card-body>
      </c-card>
    }

    <c-card class="mb-4">
      <c-card-body>
        <h1 class="h4 card-title mb-0">Ocorrências</h1>
        <p class="small text-body-secondary">Situações da cotação e das seguradoras que precisam de atenção técnica.</p>

        <c-row class="g-2 mb-3">
          <c-col md="4" xs="12">
            <label cLabel for="issue-filter-type">Tipo</label>
            <select (change)="filter('type', $event)" cSelect id="issue-filter-type">
              <option [selected]="!query().type" value="">Todos</option>
              @for (type of types; track type) {
                <option [selected]="type === query().type" [value]="type">{{ typeLabel(type) }}</option>
              }
            </select>
          </c-col>
          <c-col md="4" xs="12">
            <label cLabel for="issue-filter-status">Situação</label>
            <select (change)="filter('status', $event)" cSelect id="issue-filter-status">
              <option [selected]="!query().status" value="">Todas</option>
              @for (status of statuses; track status) {
                <option [selected]="status === query().status" [value]="status">{{ issueStatus(status).label }}</option>
              }
            </select>
          </c-col>
        </c-row>

        @if (loadError(); as error) {
          <app-error-state (retry)="issues.reload()" [error]="error" message="Não foi possível carregar as ocorrências." />
        } @else if (!page()) {
          <app-loading-state label="Carregando ocorrências…" />
        } @else if (!items().length) {
          <app-empty-state message="Nenhuma ocorrência com estes filtros." />
        } @else {
          <div class="table-responsive">
            <table cTable class="align-middle mb-0">
              <caption class="visually-hidden">Ocorrências operacionais</caption>
              <thead>
                <tr>
                  <th scope="col">Tipo</th>
                  <th scope="col">Situação</th>
                  <th scope="col">Vista</th>
                  <th scope="col">Resumo</th>
                  <th scope="col">Cotação</th>
                  <th scope="col"><span class="visually-hidden">Ações</span></th>
                </tr>
              </thead>
              <tbody>
                @for (issue of items(); track issue.id) {
                  <tr>
                    <td>{{ typeLabel(issue.type) }}</td>
                    <td><c-badge [color]="issueStatus(issue.status).color">{{ issueStatus(issue.status).label }}</c-badge></td>
                    <td class="small">
                      <span [title]="(issue.lastSeenAt | dateTime)">Última: {{ issue.lastSeenAt | relativeTime }}</span>
                      <span [title]="(issue.firstSeenAt | dateTime)" class="d-block text-body-secondary">Primeira: {{ issue.firstSeenAt | relativeTime }}</span>
                    </td>
                    <td class="small">{{ issue.safeSummary }}</td>
                    <td>
                      @if (issue.quoteId; as quoteId) {
                        @if (canReadQuotes()) {
                          <a [routerLink]="quoteLink(quoteId)" [title]="quoteId">{{ shortId(quoteId) }}</a>
                        } @else {
                          <span [title]="quoteId">{{ shortId(quoteId) }}</span>
                        }
                      } @else {
                        <span class="text-body-secondary">—</span>
                      }
                    </td>
                    <td class="text-end">
                      @if (canReconcile(issue)) {
                        <button (click)="openReconcile(issue)" cButton color="warning" size="sm" type="button" variant="outline">
                          Reconciliar<span class="visually-hidden"> a consulta {{ issue.queryId }}</span>
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (!loadError()) {
          <app-cursor-pagination
            (cursorChange)="navigate({ cursor: $event ?? null })"
            (limitChange)="navigate({ limit: $event, cursor: null })"
            [cursor]="query().cursor"
            [disabled]="issues.isLoading()"
            [limit]="query().limit"
            [nextCursor]="page()?.nextCursor"
          />
        }
      </c-card-body>
    </c-card>

    <app-reconciliation-dialog
      (cancelled)="closeReconcile()"
      (confirm)="reconcile($event)"
      (recover)="recoverReconcile()"
      (visibleChange)="!$event && closeReconcile()"
      [busy]="reconcileMutation()?.state() === 'sending'"
      [error]="reconcileMutation()?.error() ?? null"
      [issue]="reconciling()"
      [pendingReason]="pendingReason()"
      [unknown]="reconcileMutation()?.state() === 'unknown'"
      [visible]="reconciling() !== null"
    />
  `,
})
export class OperationsPageComponent {
  readonly #route = inject(ActivatedRoute);
  readonly #router = inject(Router);
  readonly #session = inject(SessionStore);
  readonly #storage = inject(SessionScopedStorage);
  readonly #toast = inject(ToastService);
  readonly tracker = inject(OperationTracker);

  readonly #params = toSignal(this.#route.queryParamMap, { requireSync: true });
  readonly query = computed(() => readIssueListQuery(this.#params()));
  readonly issues = listOperationIssuesResource(() => ({ query: issueListRequest(this.query()) }));
  readonly page = computed(() => (this.issues.hasValue() ? this.issues.value() : undefined));
  readonly items = computed(() => this.page()?.items ?? []);
  readonly loadError = computed(() => {
    const error = this.issues.error();
    return error ? toApiError(error) : null;
  });
  readonly canReadQuotes = computed(() => this.#session.can('quotes:read'));

  readonly types = ISSUE_TYPE_OPTIONS;
  readonly statuses = ISSUE_STATUS_OPTIONS;
  readonly shortId = shortId;

  readonly reconciling = signal<Issue | null>(null);
  readonly reconcileMutation = signal<IdempotentMutation<
    ReconcileRequest,
    ReconcileAccepted
  > | null>(null);
  readonly pendingReason = computed(() => {
    const mutation = this.reconcileMutation();
    return mutation?.state() === 'unknown' ? (mutation.attempt()?.body.reason ?? null) : null;
  });

  typeLabel(type: IssueType): string {
    return ISSUE_TYPES[type];
  }

  issueStatus(status: IssueStatus) {
    return ISSUE_STATUSES[status];
  }

  operationStatus(status: keyof typeof OPERATION_STATUSES) {
    return OPERATION_STATUSES[status];
  }

  finished(operation: { status: keyof typeof OPERATION_STATUSES }): boolean {
    return isOperationFinished(operation.status);
  }

  errorText(error: Parameters<typeof errorMessage>[0]): string {
    return errorMessage(error);
  }

  /** A ocorrência não informa a versão; a 1 sempre existe, e o aviso de versão substituída leva à atual. */
  quoteLink(quoteId: string): readonly unknown[] {
    return ['/quotes', quoteId, 'versions', 1];
  }

  /** A ação vale com a permissão, a consulta e o `RECONCILE` que o servidor oferece na ocorrência. */
  canReconcile(issue: Issue): boolean {
    return (
      !!issue.queryId &&
      issue.allowedActions.includes('RECONCILE') &&
      this.#session.can('operations:reconcile')
    );
  }

  filter(field: 'type' | 'status', event: Event): void {
    const value = (event.target as HTMLSelectElement).value || null;
    this.navigate({ [field]: value, cursor: null });
  }

  navigate(changes: Readonly<Record<string, string | number | null>>): void {
    void this.#router.navigate([], {
      relativeTo: this.#route,
      queryParams: changes,
      queryParamsHandling: 'merge',
    });
  }

  openReconcile(issue: Issue): void {
    const queryId = issue.queryId;
    if (!queryId) {
      return;
    }
    this.reconcileMutation.set(
      untracked(
        () =>
          new IdempotentMutation<ReconcileRequest, ReconcileAccepted>({
            send: (key, body) =>
              apiData(
                reconcileQuery({ path: { queryId }, body, headers: { 'Idempotency-Key': key } }),
              ),
            store: this.#storage.attemptStore<ReconcileRequest>(`reconcile.${queryId}`),
          }),
      ),
    );
    this.reconciling.set(issue);
  }

  closeReconcile(): void {
    if (this.reconcileMutation()?.state() !== 'sending') {
      this.reconciling.set(null);
    }
  }

  async reconcile(reason: string): Promise<void> {
    const mutation = this.reconcileMutation();
    if (mutation) {
      this.#settle(await mutation.submit({ reason }));
    }
  }

  async recoverReconcile(): Promise<void> {
    const mutation = this.reconcileMutation();
    if (mutation) {
      this.#settle(await mutation.retry());
    }
  }

  #settle(accepted: ReconcileAccepted | null): void {
    const issue = this.reconciling();
    if (!accepted || !issue?.queryId) {
      return;
    }
    this.tracker.track(accepted.operationId, issue.queryId);
    this.#toast.success(
      'Reconciliação registrada. Acompanhe o resultado: o pedido aceito não significa consulta recuperada.',
    );
    this.reconciling.set(null);
    this.issues.reload();
  }
}
