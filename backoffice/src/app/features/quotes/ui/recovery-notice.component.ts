import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { AlertComponent, ButtonDirective, SpinnerComponent } from '@coreui/angular';

/**
 * Resultado desconhecido de um envio: o servidor pode ter gravado a intenção sem que a resposta chegasse. A tela não
 * declara falha nem inicia outra solicitação; a ação reenvia a mesma chave e o mesmo corpo, sem duplicar.
 */
@Component({
  selector: 'app-recovery-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent, ButtonDirective, SpinnerComponent],
  template: `
    <c-alert color="warning" role="alert">
      <p class="fw-semibold mb-1">{{ title() }}</p>
      <p class="mb-2">{{ message() }}</p>
      <button (click)="retry.emit()" [disabled]="busy()" cButton color="warning" size="sm" type="button">
        @if (busy()) {
          <c-spinner aria-hidden="true" class="me-2" label="" size="sm" />
        }
        {{ actionLabel() }}
      </button>
    </c-alert>
  `,
})
export class RecoveryNoticeComponent {
  readonly title = input('Não recebemos a confirmação do servidor.');
  readonly message = input.required<string>();
  readonly actionLabel = input('Verificar envio');
  readonly busy = input(false);

  readonly retry = output<void>();
}
