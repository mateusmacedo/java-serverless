import { DOCUMENT, inject, InjectionToken } from '@angular/core';

/** Troca de página com recarga completa, que descarta todo estado em memória. Os testes trocam pela própria. */
export interface BrowserLocation {
  assign(url: string): void;
}

export const BROWSER_LOCATION = new InjectionToken<BrowserLocation>('BROWSER_LOCATION', {
  providedIn: 'root',
  factory: () => {
    const view = inject(DOCUMENT).defaultView;
    return { assign: (url) => view?.location.assign(url) };
  },
});

/**
 * Endereço de retorno depois do login, aceito só quando é um caminho deste painel: começa com uma barra, não é
 * protocolo relativo (`//host`) nem a própria tela de login. Fora disso, volta ao início.
 */
export function safeReturnUrl(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return '/';
  }
  return value === '/login' || value.startsWith('/login?') || value.startsWith('/login/')
    ? '/'
    : value;
}
