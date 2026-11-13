import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { AlertComponent } from '@coreui/angular';

import { type ApiError, errorMessage } from '../../util/api-error';

/**
 * Alerta de um erro da API no topo do formulário ou da tela: a mensagem, a lista dos campos inválidos que o
 * formulário não mostra e o `correlationId` para o suporte. O código cru nunca aparece.
 */
@Component({
  selector: 'app-error-alert',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent],
  template: `
    @if (error(); as error) {
      <c-alert [color]="color()" role="alert">
        @if (heading()) {
          <p class="fw-semibold mb-1">{{ heading() }}</p>
        }
        <p class="mb-0">{{ message() }}</p>
        @if (fieldErrors().length) {
          <ul class="mb-0 mt-2 ps-3">
            @for (fieldError of fieldErrors(); track $index) {
              <li><strong>{{ fieldError.label }}</strong>: {{ fieldError.message }}</li>
            }
          </ul>
        }
        @if (error.correlationId) {
          <p class="small mb-0 mt-2">Código para o suporte: <code>{{ error.correlationId }}</code></p>
        }
      </c-alert>
    }
  `,
})
export class ErrorAlertComponent {
  readonly error = input<ApiError | null | undefined>();
  /** Frase antes da mensagem, como "Não foi possível enviar a solicitação". */
  readonly heading = input<string | undefined>();
  readonly color = input<'danger' | 'warning'>('danger');
  /** Nome de cada campo do formulário pelo caminho do contrato, para a lista dos campos inválidos. */
  readonly fieldLabels = input<Readonly<Record<string, string>>>({});
  /** Caminhos que o formulário já mostra embaixo do campo e que não se repetem no alerta. */
  readonly shownPaths = input<readonly string[]>([]);

  readonly message = computed(() => errorMessage(this.error()));
  readonly fieldErrors = computed(() =>
    (this.error()?.fieldErrors ?? [])
      .filter(({ path }) => !this.shownPaths().includes(path))
      .map(({ path, message }) => ({ label: this.fieldLabels()[path] ?? path, message })),
  );
}
