import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  model,
  output,
  untracked,
} from '@angular/core';
import {
  ButtonCloseDirective,
  ButtonDirective,
  ModalBodyComponent,
  ModalComponent,
  ModalFooterComponent,
  ModalHeaderComponent,
  ModalTitleDirective,
  SpinnerComponent,
} from '@coreui/angular';

let nextId = 0;

/**
 * Modal de confirmação do template, com Cancelar em `btn-secondary`. O corpo vem por projeção, com o que a ação faz.
 * Ao fechar, o foco volta ao botão que abriu o modal (ou ao `returnFocus`). Durante a gravação (`busy`), os botões
 * ficam travados e a confirmação mostra o spinner.
 */
@Component({
  selector: 'app-confirm-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonCloseDirective,
    ButtonDirective,
    ModalBodyComponent,
    ModalComponent,
    ModalFooterComponent,
    ModalHeaderComponent,
    ModalTitleDirective,
    SpinnerComponent,
  ],
  template: `
    <c-modal [(visible)]="visible" [attr.aria-labelledby]="titleId" [backdrop]="busy() ? 'static' : true" [id]="modalId" [keyboard]="!busy()">
      <c-modal-header>
        <h5 [id]="titleId" cModalTitle>{{ title() }}</h5>
        <button (click)="cancel()" [disabled]="busy()" aria-label="Fechar" cButtonClose></button>
      </c-modal-header>
      <c-modal-body>
        <ng-content />
      </c-modal-body>
      <c-modal-footer>
        <button (click)="cancel()" [disabled]="busy()" cButton color="secondary" type="button">{{ cancelLabel() }}</button>
        <button
          (click)="confirm.emit()"
          [color]="confirmColor()"
          [disabled]="busy() || confirmDisabled()"
          cButton
          type="button"
        >
          @if (busy()) {
            <c-spinner aria-hidden="true" label="" class="me-2" size="sm" />
          }
          {{ confirmLabel() }}
        </button>
      </c-modal-footer>
    </c-modal>
  `,
})
export class ConfirmModalComponent {
  readonly visible = model(false);
  readonly title = input.required<string>();
  readonly confirmLabel = input('Confirmar');
  readonly confirmColor = input<'primary' | 'danger' | 'warning' | 'success'>('primary');
  readonly cancelLabel = input('Cancelar');
  /** Gravação em andamento: trava os botões, o fechamento e mostra o spinner na confirmação. */
  readonly busy = input(false);
  /** Trava só a confirmação, sem spinner, como enquanto os dados atuais carregam. */
  readonly confirmDisabled = input(false);
  /** Onde pôr o foco ao fechar, quando o elemento que abriu o modal some com ele. */
  readonly returnFocus = input<HTMLElement | null | undefined>();

  /** A pessoa confirmou; a tela grava e fecha o modal (`visible`) no fim. */
  readonly confirm = output<void>();
  readonly cancelled = output<void>();

  readonly modalId = `app-confirm-modal-${nextId++}`;
  readonly titleId = `${this.modalId}-title`;

  #wasVisible = false;

  constructor() {
    effect(() => {
      const visible = this.visible();
      const target = untracked(this.returnFocus);
      if (this.#wasVisible && !visible && target) {
        // WHY: espera o modal do CoreUI devolver o foco dele, que roda depois do fechamento.
        setTimeout(() => target.focus());
      }
      this.#wasVisible = visible;
    });
  }

  cancel(): void {
    this.visible.set(false);
    this.cancelled.emit();
  }
}
