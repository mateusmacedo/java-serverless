// WHY: o jsdom não tem a propriedade `inert`, que o alerta, o modal e o toast do CoreUI ligam no host (NG0303).
if (!('inert' in HTMLElement.prototype)) {
  Object.defineProperty(HTMLElement.prototype, 'inert', {
    configurable: true,
    get(this: HTMLElement) {
      return this.hasAttribute('inert');
    },
    set(this: HTMLElement, value: boolean) {
      this.toggleAttribute('inert', !!value);
    },
  });
}

// WHY: o jsdom não tem `IntersectionObserver`, que componentes do CoreUI criam ao entrar na tela.
if (!('IntersectionObserver' in globalThis)) {
  class InertIntersectionObserver {
    observe(): void {
      return undefined;
    }
    unobserve(): void {
      return undefined;
    }
    disconnect(): void {
      return undefined;
    }
    takeRecords(): [] {
      return [];
    }
  }
  Object.defineProperty(globalThis, 'IntersectionObserver', {
    configurable: true,
    value: InertIntersectionObserver,
  });
}
