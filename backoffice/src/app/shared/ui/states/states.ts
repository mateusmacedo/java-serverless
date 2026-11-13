import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import {
  ButtonDirective,
  ColDirective,
  PlaceholderAnimationDirective,
  PlaceholderDirective,
} from '@coreui/angular';

import { type ApiError, errorMessage } from '../../util/api-error';

/** Estado vazio de uma lista: o texto e a ação projetada ("Nenhuma cotação ainda." com "Nova cotação"). */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="text-center text-body-secondary py-5">
      <p class="mb-2">{{ message() }}</p>
      <ng-content />
    </div>
  `,
})
export class EmptyStateComponent {
  readonly message = input.required<string>();
}

/**
 * Erro ao carregar uma lista ou um registro, com o motivo, o `correlationId` para o suporte e o botão da ação
 * ("Tentar de novo", por padrão).
 */
@Component({
  selector: 'app-error-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ButtonDirective],
  template: `
    <div class="text-center py-5" role="alert">
      <p class="mb-1">{{ message() }}</p>
      @if (detail()) {
        <p class="small text-body-secondary mb-1">{{ detail() }}</p>
      }
      @if (error()?.correlationId; as correlationId) {
        <p class="small text-body-secondary mb-3">Código para o suporte: <code>{{ correlationId }}</code></p>
      }
      <button (click)="retry.emit()" cButton class="mt-2" color="primary" type="button" variant="outline">
        {{ actionLabel() }}
      </button>
    </div>
  `,
})
export class ErrorStateComponent {
  readonly message = input('Não foi possível carregar os dados.');
  readonly error = input<ApiError | null | undefined>();
  /** Texto do botão, para quando a saída não é repetir a chamada, como "Entrar de novo". */
  readonly actionLabel = input('Tentar de novo');

  readonly retry = output<void>();

  readonly detail = computed(() => errorMessage(this.error()));
}

const WIDTHS = [12, 10, 11, 8, 9, 7];

/** Carregamento com o `Placeholder` do CoreUI: linhas que piscam no lugar do conteúdo. */
@Component({
  selector: 'app-loading-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ColDirective, PlaceholderAnimationDirective, PlaceholderDirective],
  template: `
    <div aria-busy="true" class="py-2" role="status">
      <span class="visually-hidden">{{ label() }}</span>
      @for (width of widths(); track $index) {
        <p aria-hidden="true" cPlaceholderAnimation="glow" class="mb-2">
          <span [cCol]="width" cPlaceholder></span>
        </p>
      }
    </div>
  `,
})
export class LoadingStateComponent {
  readonly rows = input(5);
  /** Texto para leitor de tela. */
  readonly label = input('Carregando…');

  readonly widths = computed(() =>
    Array.from({ length: this.rows() }, (_, index) => WIDTHS[index % WIDTHS.length]),
  );
}
