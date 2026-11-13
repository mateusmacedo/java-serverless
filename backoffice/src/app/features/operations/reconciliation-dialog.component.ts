import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { FormControlDirective, FormLabelDirective, FormTextDirective } from '@coreui/angular';

import { type Issue, ReconcileRequestSchema } from '../../shared/api';
import { ConfirmModalComponent } from '../../shared/ui/confirm/confirm-modal.component';
import { ErrorAlertComponent } from '../../shared/ui/error-alert/error-alert.component';
import { controlMessage } from '../../shared/ui/forms/form-errors';
import { RelativeTimePipe } from '../../shared/ui/pipes';
import type { ApiError } from '../../shared/util/api-error';
import { RecoveryNoticeComponent } from '../quotes/ui/recovery-notice.component';
import { ISSUE_TYPES } from './operations-presentation';

/** Tamanho máximo do motivo, lido do contrato. */
const REASON_MAX_LENGTH = ReconcileRequestSchema.properties.reason.maxLength;

/**
 * Pedido de reconciliação manual (PRD §10): o motivo é obrigatório e fica na auditoria; o modal confirma que o pedido
 * não recupera a consulta por si só. Sem resposta, a verificação reenvia o mesmo pedido.
 */
@Component({
  selector: 'app-reconciliation-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ConfirmModalComponent,
    ErrorAlertComponent,
    FormControlDirective,
    FormLabelDirective,
    FormTextDirective,
    ReactiveFormsModule,
    RecoveryNoticeComponent,
    RelativeTimePipe,
  ],
  template: `
    <app-confirm-modal
      (cancelled)="cancelled.emit()"
      (confirm)="submit()"
      [(visible)]="visible"
      [busy]="busy()"
      [confirmDisabled]="unknown()"
      confirmColor="warning"
      confirmLabel="Pedir reconciliação"
      title="Pedir reconciliação"
    >
      @if (issue(); as current) {
        <p class="mb-2">
          <strong>{{ typeLabel(current) }}</strong>, vista pela última vez {{ current.lastSeenAt | relativeTime }}.
        </p>
        <p class="small text-body-secondary">{{ current.safeSummary }}</p>
      }
      <p class="small">
        O pedido é registrado com o motivo e o seu usuário. Uma reconciliação aceita não significa consulta recuperada:
        acompanhe o resultado na lista de reconciliações.
      </p>
      <label cLabel for="reconcile-reason">Motivo</label>
      <textarea
        [attr.aria-describedby]="message() ? 'reconcile-reason-error reconcile-reason-help' : 'reconcile-reason-help'"
        [attr.aria-invalid]="!!message()"
        [attr.maxlength]="maxLength"
        [formControl]="reason"
        [valid]="message() ? false : undefined"
        cFormControl
        id="reconcile-reason"
        rows="3"
      ></textarea>
      <div cFormText id="reconcile-reason-help">Até {{ maxLength }} caracteres. Não inclua dados pessoais.</div>
      @if (message(); as text) {
        <div class="invalid-feedback d-block" id="reconcile-reason-error">{{ text }}</div>
      }
      @if (unknown()) {
        <app-recovery-notice
          (retry)="recover.emit()"
          [busy]="busy()"
          actionLabel="Verificar pedido"
          message="O pedido pode ter sido registrado. Verifique enviando de novo o mesmo pedido, que não será duplicado."
        />
      } @else if (error(); as failure) {
        <app-error-alert [error]="failure" class="d-block mt-3" heading="A reconciliação não foi registrada" />
      }
    </app-confirm-modal>
  `,
})
export class ReconciliationDialogComponent {
  readonly visible = model(false);
  readonly issue = input<Issue | null>(null);
  readonly busy = input(false);
  readonly unknown = input(false);
  readonly error = input<ApiError | null>(null);
  /** Motivo de uma tentativa sem resposta, que volta ao campo para o reenvio igual. */
  readonly pendingReason = input<string | null>(null);

  readonly confirm = output<string>();
  readonly recover = output<void>();
  readonly cancelled = output<void>();

  readonly maxLength = REASON_MAX_LENGTH;
  readonly reason = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required, Validators.maxLength(REASON_MAX_LENGTH)],
  });
  readonly #submitted = signal(false);
  readonly #version = signal(0);
  readonly message = computed(() => {
    this.#version();
    return this.#submitted() || this.reason.touched ? controlMessage(this.reason) : null;
  });

  constructor() {
    this.reason.events.subscribe(() => this.#version.update((value) => value + 1));
    effect(() => {
      const open = this.visible();
      const pending = this.pendingReason();
      untracked(() => {
        if (open) {
          this.#submitted.set(false);
          this.reason.reset(pending ?? '');
        }
        if (pending) {
          this.reason.disable();
        } else {
          this.reason.enable();
        }
      });
    });
  }

  typeLabel(issue: Issue): string {
    return ISSUE_TYPES[issue.type];
  }

  submit(): void {
    this.#submitted.set(true);
    const reason = this.reason.value.trim();
    if (!reason) {
      this.reason.setValue('');
      this.reason.markAsTouched();
      return;
    }
    if (this.reason.valid) {
      this.confirm.emit(reason);
    }
  }
}
