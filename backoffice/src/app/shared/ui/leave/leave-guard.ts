import { computed, DestroyRef, DOCUMENT, inject, Injectable, signal } from '@angular/core';
import type { CanDeactivateFn } from '@angular/router';

/**
 * Tela que pode pedir confirmação antes de sair, como o formulário com alterações não enviadas. A tela implementa
 * `leaveWarning`, que um `computed` atende, e a rota dela declara `canDeactivate: [leaveGuard]`.
 */
export interface LeaveGuarded {
  /** Aviso para quem sai agora; `null` libera a saída sem perguntar. */
  leaveWarning(): string | null;
}

export const UNSENT_CHANGES_WARNING =
  'Os dados que você preencheu ainda não foram enviados e se perdem se você sair desta tela.';

export const PENDING_SUBMISSION_WARNING =
  'A confirmação do envio ainda não chegou. Se sair, a verificação continua disponível ao voltar a esta tela nesta aba.';

interface PendingQuestion {
  readonly message: string;
  readonly answer: (leave: boolean) => void;
}

/**
 * Pergunta se a pessoa quer sair da tela. Com o `app-leave-confirm-host` no layout, a pergunta abre o modal do
 * template; sem ele, como nos testes de uma tela, cai no `confirm` do navegador.
 */
@Injectable({ providedIn: 'root' })
export class LeaveConfirmation {
  readonly #document = inject(DOCUMENT);
  readonly #pending = signal<PendingQuestion | null>(null);
  #hosts = 0;

  /** Aviso da pergunta aberta, que o host mostra no modal. */
  readonly message = computed(() => this.#pending()?.message ?? null);

  /** Abre a pergunta e resolve `true` quando a pessoa confirma a saída. */
  ask(message: string): Promise<boolean> {
    if (this.#hosts === 0) {
      return Promise.resolve(this.#document.defaultView?.confirm(message) ?? true);
    }
    this.#pending()?.answer(false);
    return new Promise((resolve) => this.#pending.set({ message, answer: resolve }));
  }

  /** Resposta do modal; o host chama ao confirmar ou ao fechar. */
  answer(leave: boolean): void {
    const pending = this.#pending();
    this.#pending.set(null);
    pending?.answer(leave);
  }

  /** O host se registra ao entrar no layout e devolve a função que o desliga. */
  attachHost(): () => void {
    this.#hosts++;
    return () => {
      this.#hosts--;
      this.answer(false);
    };
  }
}

/** Guard `canDeactivate` das telas com `LeaveGuarded`: sem aviso, libera; com aviso, pergunta antes de sair. */
export const leaveGuard: CanDeactivateFn<Partial<LeaveGuarded> | null> = (component) => {
  const warning = component?.leaveWarning?.() ?? null;
  return warning === null ? true : inject(LeaveConfirmation).ask(warning);
};

/**
 * Liga o aviso do navegador ao fechar ou recarregar a aba enquanto `warning` devolver um texto. Chame no construtor da
 * tela; o aviso sai quando ela é destruída. O navegador mostra o texto dele, não o da tela.
 */
export function warnBeforeUnload(warning: () => string | null): void {
  const view = inject(DOCUMENT).defaultView;
  if (!view) {
    return;
  }
  const listener = (event: BeforeUnloadEvent) => {
    if (warning() !== null) {
      event.preventDefault();
    }
  };
  view.addEventListener('beforeunload', listener);
  inject(DestroyRef).onDestroy(() => view.removeEventListener('beforeunload', listener));
}
