import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject } from '@angular/core';

import { ConfirmModalComponent } from '../confirm/confirm-modal.component';
import { LeaveConfirmation } from './leave-guard';

/**
 * Modal do template que pergunta se a pessoa quer sair da tela com dados não enviados (`leaveGuard`). Vai uma vez no
 * layout, ao lado do `app-toast-host`.
 */
@Component({
  selector: 'app-leave-confirm-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ConfirmModalComponent],
  template: `
    <app-confirm-modal
      (cancelled)="answer(false)"
      (confirm)="answer(true)"
      (visibleChange)="!$event && answer(false)"
      [visible]="open()"
      cancelLabel="Continuar na tela"
      confirmColor="danger"
      confirmLabel="Sair"
      title="Sair desta tela?"
    >
      <p class="mb-0">{{ message() }}</p>
    </app-confirm-modal>
  `,
})
export class LeaveConfirmHostComponent {
  readonly #confirmation = inject(LeaveConfirmation);
  readonly message = this.#confirmation.message;
  readonly open = computed(() => this.message() !== null);

  constructor() {
    inject(DestroyRef).onDestroy(this.#confirmation.attachHost());
  }

  answer(leave: boolean): void {
    if (this.open()) {
      this.#confirmation.answer(leave);
    }
  }
}
