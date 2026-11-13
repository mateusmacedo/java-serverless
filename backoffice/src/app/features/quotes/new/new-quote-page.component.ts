import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  Injector,
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
import { take } from 'rxjs';

import { createQuote, type CreateQuoteRequest, type QuoteAccepted } from '../../../shared/api';
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
import { ToastService } from '../../../shared/ui/toast/toast.service';
import { apiData } from '../../../shared/util/api-error';
import {
  createQuoteForm,
  QUOTE_FIELD_LABELS,
  type QuoteFormLabels,
  toCreateQuoteRequest,
} from '../form/quote-form';
import { QuoteFormComponent } from '../form/quote-form.component';
import { QuoteReviewComponent } from '../form/quote-review.component';
import { shortId } from '../quote-presentation';
import { RecoveryNoticeComponent } from '../ui/recovery-notice.component';

/**
 * Nova cotação (`/quotes/new`, PRD §4 e FE03, FE04 e FE14): formulário, revisão e envio idempotente. Duplo clique é
 * uma intenção só; sem resposta, a tela trava a edição e oferece verificar o envio com a mesma chave e o mesmo corpo,
 * inclusive depois de recarregada. O rascunho fica só em memória.
 */
@Component({
  selector: 'app-new-quote-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    ButtonDirective,
    CardBodyComponent,
    CardComponent,
    ErrorAlertComponent,
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
        <h1 class="h4 card-title mb-0">Nova cotação</h1>
        <p class="small text-body-secondary">Escolha o proponente, o veículo, o condutor, o uso, a cobertura e a vigência.</p>

        @if (step() === 'form') {
          @if (mutation.error(); as error) {
            <app-error-alert
              [error]="error"
              [fieldLabels]="fieldLabels"
              [shownPaths]="appliedPaths()"
              heading="Não foi possível enviar a solicitação"
            />
          }
          <form #formElement (ngSubmit)="review()" [formGroup]="form" novalidate>
            <app-quote-form
              (labelsChange)="labels.set($event)"
              [form]="form"
              [submitted]="submitted()"
            />
            <div class="d-flex flex-wrap gap-2 mt-4">
              <button cButton color="primary" type="submit">Revisar solicitação</button>
              <a cButton color="secondary" routerLink="/quotes" variant="ghost">Cancelar</a>
            </div>
          </form>
        } @else if (request(); as body) {
          <h2 #reviewHeading class="h5" tabindex="-1">Revise antes de enviar</h2>
          <app-quote-review [labels]="labels()" [request]="body" />
          <c-alert class="mt-3" color="info">
            O envio inicia as consultas às seguradoras. Não é uma contratação: nenhuma proposta é enviada e nenhum seguro é
            contratado nesta etapa.
          </c-alert>
          @if (mutation.state() === 'unknown') {
            <app-recovery-notice
              (retry)="recover()"
              [busy]="false"
              message="A solicitação pode ter sido registrada. Verifique enviando de novo a mesma solicitação: ela não será duplicada. Enquanto isso, a edição fica bloqueada."
            />
          } @else if (mutation.error(); as error) {
            <app-error-alert [error]="error" [fieldLabels]="fieldLabels" heading="Não foi possível enviar a solicitação" />
          }
          <div class="d-flex flex-wrap gap-2">
            <button (click)="send()" [disabled]="mutation.locked()" cButton color="primary" type="button">
              @if (mutation.state() === 'sending') {
                <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />
              }
              {{ mutation.state() === 'retryable' ? 'Tentar de novo' : 'Enviar solicitação' }}
            </button>
            <button (click)="backToForm()" [disabled]="mutation.locked()" cButton color="secondary" type="button" variant="outline">
              Voltar e editar
            </button>
          </div>
          <p aria-live="polite" class="small text-body-secondary mt-2 mb-0">
            @if (mutation.state() === 'sending') {
              Enviando a solicitação…
            }
          </p>
        }
      </c-card-body>
    </c-card>
  `,
})
export class NewQuotePageComponent implements LeaveGuarded {
  readonly #router = inject(Router);
  readonly #toast = inject(ToastService);
  readonly #analytics = inject(Analytics);
  readonly #injector = inject(Injector);

  readonly form = createQuoteForm();
  readonly step = signal<'form' | 'review'>('form');
  readonly submitted = signal(false);
  readonly labels = signal<QuoteFormLabels>({});
  readonly request = signal<CreateQuoteRequest | null>(null);
  readonly appliedPaths = signal<string[]>([]);
  readonly fieldLabels = QUOTE_FIELD_LABELS;
  readonly formElement = viewChild<ElementRef<HTMLFormElement>>('formElement');
  readonly reviewHeading = viewChild<ElementRef<HTMLElement>>('reviewHeading');

  readonly mutation = new IdempotentMutation<CreateQuoteRequest, QuoteAccepted>({
    send: (key, body) => apiData(createQuote({ body, headers: { 'Idempotency-Key': key } })),
    store: inject(SessionScopedStorage).attemptStore<CreateQuoteRequest>('create-quote'),
  });

  readonly #dirty = signal(false);
  readonly #leaveWarning = computed(() => {
    const state = this.mutation.state();
    if (state === 'succeeded') {
      return null;
    }
    if (state === 'unknown' || state === 'sending') {
      return PENDING_SUBMISSION_WARNING;
    }
    return this.#dirty() ? UNSENT_CHANGES_WARNING : null;
  });

  constructor() {
    const pending = this.mutation.attempt();
    if (pending) {
      // NOTE: a tentativa sem resposta guardada na aba volta com o corpo enviado, para a verificação reenviá-lo igual.
      this.form.setValue({
        applicantRef: pending.body.applicantRef,
        risk: { ...pending.body.risk },
        coverageProfile: pending.body.coverageProfile,
        desiredStartDate: pending.body.desiredStartDate,
      });
      this.request.set(pending.body);
      this.step.set('review');
    }
    this.form.valueChanges.pipe(take(1)).subscribe(() => this.#analytics.track('QuoteFormStarted'));
    this.form.events.subscribe(() => this.#dirty.set(this.form.dirty));
    effect(() => {
      const locked = this.mutation.locked();
      // WHY: reabilitar só o que está desabilitado; o `enable` refaz a validação e apagaria os erros do 400.
      untracked(() => setLocked(this.form, locked));
    });
    warnBeforeUnload(() => this.leaveWarning());
  }

  leaveWarning(): string | null {
    return this.#leaveWarning();
  }

  /** Confere os campos no cliente; com erro, nenhuma chamada sai e o foco vai ao primeiro campo inválido. */
  review(): void {
    this.submitted.set(true);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      setTimeout(() => focusFirstInvalid(this.formElement()?.nativeElement));
      return;
    }
    this.request.set(toCreateQuoteRequest(this.form));
    this.step.set('review');
    setTimeout(() => this.reviewHeading()?.nativeElement.focus());
  }

  backToForm(): void {
    if (!this.mutation.locked()) {
      this.step.set('form');
    }
  }

  async send(): Promise<void> {
    const body = this.request();
    if (body) {
      this.#settle(await this.mutation.submit(body));
    }
  }

  async recover(): Promise<void> {
    this.#analytics.track('UiRecoveryRequested', { code: 'CREATE_QUOTE' });
    this.#settle(await this.mutation.retry());
  }

  #settle(accepted: QuoteAccepted | null): void {
    if (accepted) {
      this.#analytics.track('QuoteSubmissionAccepted', {
        quoteId: accepted.quoteId,
        version: accepted.version,
      });
      this.#toast.success(`Solicitação recebida. Protocolo ${shortId(accepted.quoteId)}`);
      this.form.markAsPristine();
      void this.#router.navigate(['/quotes', accepted.quoteId, 'versions', accepted.version]);
      return;
    }
    const error = this.mutation.error();
    if (this.mutation.state() === 'rejected' && error?.status === 400) {
      setLocked(this.form, false);
      const paths = serverErrorPaths(this.form, error);
      this.appliedPaths.set(paths);
      if (paths.length) {
        this.step.set('form');
        // WHY: os campos voltam à tela, e o vínculo de cada um refaz a validação; os erros do 400 entram depois.
        afterNextRender(
          () => {
            applyServerErrors(this.form, error);
            focusFirstInvalid(this.formElement()?.nativeElement);
          },
          { injector: this.#injector },
        );
      }
    }
  }
}
