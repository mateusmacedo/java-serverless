import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CardBodyComponent, CardComponent } from '@coreui/angular';

/** Início de quem entrou sem nenhuma permissão do painel na organização. */
@Component({
  selector: 'app-no-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CardBodyComponent, CardComponent],
  template: `
    <c-card class="mb-4">
      <c-card-body class="text-center py-5">
        <h1 class="h4">Seu usuário ainda não tem acesso</h1>
        <p class="text-body-secondary mb-0">
          Nenhuma área do painel está liberada para você nesta organização. Peça o acesso a quem administra os usuários.
        </p>
      </c-card-body>
    </c-card>
  `,
})
export class NoAccessComponent {}
