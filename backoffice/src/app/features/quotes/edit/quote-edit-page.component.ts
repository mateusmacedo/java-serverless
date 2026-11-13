import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  Injector,
  input,
  numberAttribute,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  AlertComponent,
  ButtonDirective,
  CardBodyComponent,
  CardComponent,
  SpinnerComponent,
} from '@coreui/angular';

import { SessionStore } from '../../../core/auth/session-store';
import {
  createQuoteVersion,
  type CreateQuoteRequest,
  type QuoteAccepted,
} from '../../../shared/api';
import { Analytics } from '../../../shared/state/analytics';
import { IdempotentMutation } from '../../../shared/state/idempotent-mutation';
import { SessionScopedStorage } from '../../../shared/state/session-storage';
import { ErrorAlertComponent } from '../../../shared/ui/error-alert/error-alert.component';
import {
  applyServerErrors,
  focusFirstInvalid,
  serverErrorPaths,
  setLocked,
} from '../../../shared/ui/forms/form-errors';
import {
  type LeaveGuarded,
  PENDING_SUBMISSION_WARNING,
  UNSENT_CHANGES_WARNING,
  warnBeforeUnload,
} from '../../../shared/ui/leave/leave-guard';
import { ErrorStateComponent, LoadingStateComponent } from '../../../shared/ui/states/states';
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { apiData, isConflict } from '../../../shared/util/api-error';
import { QuoteVersionStore } from '../detail/quote-version-store';
import { MissingInformationPanelComponent } from '../detail/version-parts';
import {
  createQuoteForm,
  labelsFromSnapshot,
  QUOTE_FIELD_LABELS,
  type QuoteForm,
  type QuoteFormLabels,
  requestFromSnapshot,
  toCreateQuoteRequest,
} from '../form/quote-form';
import { QuoteFormComponent } from '../form/quote-form.component';
import { QuoteReviewComponent } from '../form/quote-review.component';
import { pendingControl, pendingFields, shortId } from '../quote-presentation';
import { RecoveryNoticeComponent } from '../ui/recovery-notice.component';

/** Corpo de `POST /v1/quotes/{id}/versions`: o pedido completo e a versão que a pessoa leu. */
type VersionRequest = CreateQuoteRequest & { readonly expectedVersion: number };

/**
 * Complementação (`/quotes/:id/versions/:version/edit`, PRD FE08): parte do snapshot da versão, destaca as
 * pendências e cria uma nova versão com `expectedVersion`; a versão lida continua no histórico. Num `409`, os dados
 * digitados ficam nos campos e nada no servidor é sobrescrito.
 */
@Component({
  selector: 'app-quote-edit-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [QuoteVersionStore],
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ErrorAlertComponent,
    ErrorStateComponent,
    LoadingStateComponent,
    MissingInformationPanelComponent,
    QuoteFormComponent,
    QuoteReviewComponent,
    ReactiveFormsModule,
    RecoveryNoticeComponent,
    RouterLink,
    SpinnerComponent,
  ],
  template: `
    <c-card class="mb-4">
      <c-card-body>
        <h1 class="h4 card-title mb-0">Complementar cotação {{ shortId(quoteId()) }}</h1>
        <p class="small text-body-secondary">
          A complementação cria a versão {{ version() + 1 }} a partir da versão {{ version() }}, que continua no histórico.
        </p>

        @if (store.loadError(); as error) {
          <app-error-state (retry)="store.refresh()" [error]="error" message="Não foi possível abrir a cotação." />
        } @else if (!data()) {
          <app-loading-state label="Carregando a cotação…" />
        } @else if (!canEdit() && mutation().state() !== 'unknown') {
          <c-alert color="info">
            Esta versão não aceita complementação agora: ela foi substituída, já tem uma oferta selecionada ou você não tem
            permissão para criar versões.
          </c-alert>
          <a [routerLink]="['..']" cButton color="secondary" variant="outline">Voltar à cotação</a>
        } @else if (form(); as quoteForm) {
          <app-missing-information-panel [pending]="pending()" />

          @if (conflict()) {
            <c-alert color="warning" role="alert">
              <p class="mb-2">{{ conflictMessage() }}</p>
              <button (click)="openCurrent()" [disabled]="store.refreshing()" cButton color="warning" size="sm" type="button">
                Ver a versão atual
              </button>
            </c-alert>
          }

          @if (step() === 'form') {
            @if (mutation().error(); as error) {
              @if (!conflict()) {
                <app-error-alert [error]="error" [fieldLabels]="fieldLabels" [shownPaths]="appliedPaths()" heading="Não foi possível criar a nova versão" />
              }
            }
            <form #formElement (ngSubmit)="review()" [formGroup]="quoteForm" novalidate>
              <app-quote-form
                (labelsChange)="labels.set($event)"
                [form]="quoteForm"
                [initialLabels]="initialLabels()"
                [pendingPaths]="pendingPaths()"
                [submitted]="submitted()"
              />
              <div class="d-flex flex-wrap gap-2 mt-4">
                <button cButton color="primary" type="submit">Revisar complementação</button>
                <a [routerLink]="['..']" cButton color="secondary" variant="ghost">Cancelar</a>
              </div>
            </form>
          } @else if (request(); as body) {
            <h2 #reviewHeading class="h5" tabindex="-1">Revise antes de enviar</h2>
            <app-quote-review [labels]="labels()" [request]="body" />
            <c-alert class="mt-3" color="info">
              O envio cria uma nova versão e reinicia as consultas às seguradoras. A versão {{ version() }} continua no
              histórico, sem alteração.
            </c-alert>
            @if (mutation().state() === 'unknown') {
              <app-recovery-notice
                (retry)="recover()"
                message="A nova versão pode ter sido criada. Verifique enviando de novo a mesma complementação: ela não será duplicada. Enquanto isso, a edição fica bloqueada."
              />
            } @else if (mutation().error(); as error) {
              @if (!conflict()) {
                <app-error-alert [error]="error" [fieldLabels]="fieldLabels" heading="Não foi possível criar a nova versão" />
              }
            }
            <div class="d-flex flex-wrap gap-2">
              <button (click)="send()" [disabled]="mutation().locked() || conflict()" cButton color="primary" type="button">
                @if (mutation().state() === 'sending') {
                  <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />
                }
                {{ mutation().state() === 'retryable' ? 'Tentar de novo' : 'Criar nova versão' }}
              </button>
              <button (click)="backToForm()" [disabled]="mutation().locked()" cButton color="secondary" type="button" variant="outline">
                Voltar e editar
              </button>
            </div>
          }
        }
      </c-card-body>
    </c-card>
  `,
})
export class QuoteEditPageComponent implements LeaveGuarded {
  readonly store = inject(QuoteVersionStore);
  readonly #session = inject(SessionStore);
  readonly #storage = inject(SessionScopedStorage);
  readonly #router = inject(Router);
  readonly #toast = inject(ToastService);
  readonly #analytics = inject(Analytics);
  readonly #injector = inject(Injector);

  readonly quoteId = input.required<string>();
  readonly version = input.required({ transform: numberAttribute });

  readonly data = this.store.version;
  readonly form = signal<QuoteForm | null>(null);
  readonly step = signal<'form' | 'review'>('form');
  readonly submitted = signal(false);
  readonly labels = signal<QuoteFormLabels>({});
  readonly request = signal<CreateQuoteRequest | null>(null);
  readonly appliedPaths = signal<string[]>([]);
  readonly fieldLabels = QUOTE_FIELD_LABELS;
  readonly shortId = shortId;
  readonly formElement = viewChild<ElementRef<HTMLFormElement>>('formElement');
  readonly reviewHeading = viewChild<ElementRef<HTMLElement>>('reviewHeading');

  readonly pending = computed(() => {
    const version = this.data();
    return version ? pendingFields(version) : [];
  });
  readonly pendingPaths = computed(() =>
    this.pending()
      .map((item) => pendingControl(item.field.path))
      .filter((path): path is string => path !== null),
  );
  readonly initialLabels = computed<QuoteFormLabels>(() => {
    const version = this.data();
    return version ? labelsFromSnapshot(version.snapshot) : {};
  });
  readonly canEdit = computed(() => {
    const version = this.data();
    return (
      !!version &&
      version.version === version.currentVersion &&
      version.supersededAt === null &&
      version.allowedActions.includes('CREATE_VERSION') &&
      this.#session.can('quotes:write')
    );
  });

  readonly mutation = computed(() => {
    const quoteId = this.quoteId();
    return untracked(
      () =>
        new IdempotentMutation<VersionRequest, QuoteAccepted>({
          send: (key, body) =>
            apiData(
              createQuoteVersion({ path: { quoteId }, body, headers: { 'Idempotency-Key': key } }),
            ),
          store: this.#storage.attemptStore<VersionRequest>(`create-version.${quoteId}`),
        }),
    );
  });
  readonly conflict = computed(() => {
    const error = this.mutation().error();
    return isConflict(error, 'VERSION_CONFLICT') || isConflict(error, 'QUOTE_ALREADY_SELECTED');
  });
  readonly conflictMessage = computed(() =>
    isConflict(this.mutation().error(), 'QUOTE_ALREADY_SELECTED')
      ? 'A cotação já tem uma oferta selecionada e não aceita nova versão. Os dados que você preencheu não foram enviados.'
      : 'Outra versão foi criada depois que você abriu esta tela. Os dados que você preencheu continuam nos campos e nada foi sobrescrito; consulte a versão atual antes de complementar de novo.',
  );

  readonly #dirty = signal(false);

  constructor() {
    effect(() => {
      const quoteId = this.quoteId();
      const version = this.version();
      untracked(() => this.store.open(quoteId, version));
    });
    effect(() => {
      const version = this.data();
      if (!version || untracked(this.form)) {
        return;
      }
      untracked(() => this.#createForm(requestFromSnapshot(version.snapshot)));
    });
    effect(() => {
      const form = this.form();
      const locked = this.mutation().locked();
      // WHY: reabilitar só o que está desabilitado; o `enable` refaz a validação e apagaria os erros do 400.
      untracked(() => form && setLocked(form, locked));
    });
    warnBeforeUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    const state = this.mutation().state();
    if (state === 'succeeded') {
      return null;
    }
    if (state === 'unknown' || state === 'sending') {
      return PENDING_SUBMISSION_WARNING;
    }
    return this.#dirty() ? UNSENT_CHANGES_WARNING : null;
  }

  review(): void {
    const form = this.form();
    if (!form) {
      return;
    }
    this.submitted.set(true);
    if (form.invalid) {
      form.markAllAsTouched();
      setTimeout(() => focusFirstInvalid(this.formElement()?.nativeElement));
      return;
    }
    this.request.set(toCreateQuoteRequest(form));
    this.step.set('review');
    setTimeout(() => this.reviewHeading()?.nativeElement.focus());
  }

  backToForm(): void {
    if (!this.mutation().locked()) {
      this.step.set('form');
    }
  }

  async send(): Promise<void> {
    const body = this.request();
    if (body) {
      this.#settle(await this.mutation().submit({ ...body, expectedVersion: this.version() }));
    }
  }

  async recover(): Promise<void> {
    this.#analytics.track('UiRecoveryRequested', {
      code: 'CREATE_VERSION',
      quoteId: this.quoteId(),
    });
    this.#settle(await this.mutation().retry());
  }

  /** Lê a cotação de novo e abre a versão atual que o servidor informar. */
  async openCurrent(): Promise<void> {
    await this.store.refresh();
    const current = this.data()?.currentVersion ?? this.version();
    this.#dirty.set(false);
    void this.#router.navigate(['/quotes', this.quoteId(), 'versions', current]);
  }

  #createForm(initial: CreateQuoteRequest): void {
    const pending = this.mutation().attempt();
    const form = createQuoteForm(pending?.body ?? initial);
    form.events.subscribe(() => this.#dirty.set(form.dirty));
    this.form.set(form);
    if (pending) {
      this.request.set(pending.body);
      this.step.set('review');
    }
  }

  #settle(accepted: QuoteAccepted | null): void {
    const form = this.form();
    if (accepted) {
      this.#toast.success(
        `Versão ${accepted.version} criada. A versão ${this.version()} continua no histórico.`,
      );
      form?.markAsPristine();
      this.#dirty.set(false);
      void this.#router.navigate(['/quotes', accepted.quoteId, 'versions', accepted.version]);
      return;
    }
    const error = this.mutation().error();
    if (form && this.mutation().state() === 'rejected' && error?.status === 400) {
      setLocked(form, false);
      const paths = serverErrorPaths(form, error);
      this.appliedPaths.set(paths);
      if (paths.length) {
        this.step.set('form');
        // WHY: os campos voltam à tela, e o vínculo de cada um refaz a validação; os erros do 400 entram depois.
        afterNextRender(
          () => {
            applyServerErrors(form, error);
            focusFirstInvalid(this.formElement()?.nativeElement);
          },
          { injector: this.#injector },
        );
      }
    }
  }
}
