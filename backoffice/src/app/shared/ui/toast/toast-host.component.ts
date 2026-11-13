import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  ProgressComponent,
  ToastBodyComponent,
  ToastComponent,
  ToasterComponent,
  ToastHeaderComponent,
} from '@coreui/angular';

import { formatTime } from '../../util/dates';
import { TOAST_DELAY_MS, type ToastKind, ToastService } from './toast.service';

const MARKER_COLORS: Readonly<Record<ToastKind, string>> = {
  success: 'var(--cui-success)',
  info: 'var(--cui-primary)',
  warning: 'var(--cui-warning)',
  danger: 'var(--cui-danger)',
};

/**
 * Toaster do template no canto superior direito, com os toasts do `ToastService`: marcador de cor, título, hora,
 * botão de fechar e a barra do tempo até o toast sumir. Vai uma vez no layout; os avisos são anunciados com
 * `aria-live="polite"`, sem interromper a leitura.
 */
@Component({
  selector: 'app-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ProgressComponent,
    ToastBodyComponent,
    ToastComponent,
    ToasterComponent,
    ToastHeaderComponent,
  ],
  template: `
    <c-toaster aria-live="polite" class="p-3" placement="top-end" position="fixed">
      @for (toast of toasts(); track toast.id) {
        <c-toast
          (timer)="tick(toast.id, $event)"
          (visibleChange)="visibleChange(toast.id, $event)"
          [autohide]="true"
          [delay]="delay"
          [visible]="true"
        >
          <c-toast-header ariaCloseLabel="Fechar">
            <svg aria-hidden="true" class="rounded me-2" focusable="false" height="20" width="20">
              <rect [attr.fill]="markerColor(toast.kind)" height="100%" width="100%"></rect>
            </svg>
            <strong class="me-auto">{{ toast.title }}</strong>
            <small>{{ time(toast.createdAt) }}</small>
          </c-toast-header>
          <c-toast-body>
            <p class="mb-1">{{ toast.message }}</p>
            <c-progress [value]="remaining()[toast.id] ?? 100" aria-label="Tempo do aviso" thin />
          </c-toast-body>
        </c-toast>
      }
    </c-toaster>
  `,
})
export class ToastHostComponent {
  readonly #service = inject(ToastService);
  readonly toasts = this.#service.toasts;
  readonly delay = TOAST_DELAY_MS;
  readonly remaining = signal<Readonly<Record<number, number>>>({});

  markerColor(kind: ToastKind): string {
    return MARKER_COLORS[kind];
  }

  time(createdAt: Date): string {
    return formatTime(createdAt);
  }

  tick(id: number, elapsedSeconds: number): void {
    const remaining = Math.max(0, 100 - (elapsedSeconds * 1000 * 100) / this.delay);
    this.remaining.update((values) => ({ ...values, [id]: remaining }));
  }

  visibleChange(id: number, visible: boolean): void {
    if (visible) {
      return;
    }
    this.#service.dismiss(id);
    this.remaining.update((values) => {
      const next = { ...values };
      delete next[id];
      return next;
    });
  }
}
