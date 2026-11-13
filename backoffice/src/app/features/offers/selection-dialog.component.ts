import { ChangeDetectionStrategy, Component, computed, input, model, output } from '@angular/core';
import { AlertComponent } from '@coreui/angular';

import type { Offer } from '../../shared/api';
import { ConfirmModalComponent } from '../../shared/ui/confirm/confirm-modal.component';
import { ErrorAlertComponent } from '../../shared/ui/error-alert/error-alert.component';
import { LoadingStateComponent } from '../../shared/ui/states/states';
import { DateTimePipe, MoneyPipe } from '../../shared/ui/pipes';
import type { ApiError } from '../../shared/util/api-error';
import { RecoveryNoticeComponent } from '../quotes/ui/recovery-notice.component';
import { installmentsText } from './offer-presentation';

/** Etapa do modal: lendo os dados atuais, pronto para confirmar ou sem a oferta disponível. */
export type SelectionDialogStep = 'refreshing' | 'ready' | 'unavailable';

/**
 * Confirmação da seleção (PRD §8 e FE09): antes de confirmar, a tela lê a versão e as ofertas de novo; o modal resume
 * a oferta e avisa que a ação registra a intenção de proposta, sem contratar. O sucesso só aparece com a resposta do
 * servidor; sem resposta, a verificação reenvia a mesma seleção.
 */
@Component({
  selector: 'app-selection-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    AlertComponent,
    ConfirmModalComponent,
    DateTimePipe,
    ErrorAlertComponent,
    LoadingStateComponent,
    MoneyPipe,
    RecoveryNoticeComponent,
  ],
  template: `
    <app-confirm-modal
      (cancelled)="cancelled.emit()"
      (confirm)="confirm.emit()"
      [(visible)]="visible"
      [busy]="busy()"
      [confirmDisabled]="step() !== 'ready' || unknown()"
      [returnFocus]="returnFocus()"
      confirmLabel="Registrar intenção de proposta"
      title="Selecionar oferta"
    >
      @if (step() === 'refreshing') {
        <app-loading-state [rows]="3" label="Conferindo os dados atuais da oferta…" />
      } @else if (offer(); as current) {
        <p class="mb-2"><strong>{{ current.insurerName }}</strong></p>
        <dl class="row small mb-2">
          <dt class="col-5">Prêmio total</dt>
          <dd class="col-7">{{ current.premiumTotal | money: current.currency }}</dd>
          <dt class="col-5">Parcelamento</dt>
          <dd class="col-7">{{ installments() }}</dd>
          <dt class="col-5">Coberturas</dt>
          <dd class="col-7">{{ coverageCount() }}</dd>
          <dt class="col-5">Válida até</dt>
          <dd class="col-7">{{ current.validUntil | dateTime }}</dd>
        </dl>
        @if (step() === 'unavailable') {
          <c-alert color="warning" role="alert">
            {{ unavailableMessage() }}
          </c-alert>
        } @else {
          <c-alert class="mb-2" color="info">
            Ao confirmar, você registra a intenção de proposta para esta oferta. Nenhuma proposta é enviada e o seguro não é
            contratado. A seleção é definitiva nesta cotação.
          </c-alert>
        }
      } @else {
        <c-alert color="warning" role="alert">A oferta não está mais disponível nesta versão.</c-alert>
      }
      @if (unknown()) {
        <app-recovery-notice
          (retry)="recover.emit()"
          [busy]="busy()"
          actionLabel="Verificar seleção"
          message="A seleção pode ter sido registrada. Não presuma que não foi: verifique enviando de novo a mesma seleção, que não será duplicada."
        />
      } @else if (error(); as failure) {
        <app-error-alert [error]="failure" heading="A seleção não foi registrada" />
      }
    </app-confirm-modal>
  `,
})
export class SelectionDialogComponent {
  readonly visible = model(false);
  readonly offer = input<Offer | null>(null);
  readonly step = input<SelectionDialogStep>('refreshing');
  readonly unavailableMessage = input('A oferta não pode mais ser selecionada.');
  readonly busy = input(false);
  readonly unknown = input(false);
  readonly error = input<ApiError | null>(null);
  readonly returnFocus = input<HTMLElement | null>(null);

  readonly confirm = output<void>();
  readonly recover = output<void>();
  readonly cancelled = output<void>();

  readonly installments = computed(() => {
    const offer = this.offer();
    return offer ? installmentsText(offer.installments, offer.currency) : '';
  });
  readonly coverageCount = computed(() => {
    const count = this.offer()?.coverages.length ?? 0;
    return count === 1 ? '1 cobertura' : `${count} coberturas`;
  });
}
