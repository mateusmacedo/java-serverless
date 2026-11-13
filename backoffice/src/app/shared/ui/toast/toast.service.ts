import { Injectable, signal } from '@angular/core';

/** Cor do marcador do toast. */
export type ToastKind = 'success' | 'info' | 'warning' | 'danger';

/** Tempo até o toast sumir, marcado pela barra fina do corpo. */
export const TOAST_DELAY_MS = 5000;

export interface ToastMessage {
  readonly id: number;
  readonly kind: ToastKind;
  readonly title: string;
  readonly message: string;
  readonly createdAt: Date;
}

const DEFAULT_TITLES: Readonly<Record<ToastKind, string>> = {
  success: 'Pronto',
  info: 'Aviso',
  warning: 'Atenção',
  danger: 'Erro',
};

/**
 * Avisos do painel, mostrados pelo `app-toast-host` do layout no toaster do template. Erro de tela fica no lugar
 * onde aconteceu; o toast confirma o que deu certo e traz avisos que não pertencem a um formulário.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly #toasts = signal<readonly ToastMessage[]>([]);
  readonly toasts = this.#toasts.asReadonly();
  #nextId = 1;

  show(message: string, kind: ToastKind = 'success', title?: string): number {
    const toast: ToastMessage = {
      id: this.#nextId++,
      kind,
      title: title ?? DEFAULT_TITLES[kind],
      message,
      createdAt: new Date(),
    };
    this.#toasts.update((toasts) => [...toasts, toast]);
    return toast.id;
  }

  success(message: string, title?: string): number {
    return this.show(message, 'success', title);
  }

  info(message: string, title?: string): number {
    return this.show(message, 'info', title);
  }

  warning(message: string, title?: string): number {
    return this.show(message, 'warning', title);
  }

  dismiss(id: number): void {
    this.#toasts.update((toasts) => toasts.filter((toast) => toast.id !== id));
  }
}
