import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import {
  ContainerComponent,
  ShadowOnScrollDirective,
  SidebarBrandComponent,
  SidebarComponent,
  SidebarFooterComponent,
  SidebarHeaderComponent,
  SidebarNavComponent,
  SidebarToggleDirective,
  SidebarTogglerDirective,
} from '@coreui/angular';
import { IconDirective } from '@coreui/icons-angular';
import { NgScrollbar } from 'ngx-scrollbar';
import { NgScrollHoverVisibility } from 'ngx-scrollbar/addons';

import { SessionStore } from '../core/auth/session-store';
import { LeaveConfirmHostComponent } from '../shared/ui/leave/leave-confirm-host.component';
import { ErrorStateComponent, LoadingStateComponent } from '../shared/ui/states/states';
import { ToastHostComponent } from '../shared/ui/toast/toast-host.component';
import { DefaultFooterComponent } from './default-footer.component';
import { DefaultHeaderComponent } from './default-header.component';
import { buildNav, navItems } from './nav';

/**
 * Layout do template do CoreUI: menu lateral com o que a sessão libera, cabeçalho, conteúdo e rodapé. Enquanto
 * `GET /v1/me` não responde, ou quando falha, o estado da sessão aparece no lugar da tela.
 */
@Component({
  selector: 'app-default-layout',
  templateUrl: './default-layout.component.html',
  styleUrl: './default-layout.component.scss',
  imports: [
    ContainerComponent,
    DefaultFooterComponent,
    DefaultHeaderComponent,
    ErrorStateComponent,
    IconDirective,
    LeaveConfirmHostComponent,
    LoadingStateComponent,
    NgScrollbar,
    NgScrollHoverVisibility,
    RouterLink,
    RouterOutlet,
    ShadowOnScrollDirective,
    SidebarBrandComponent,
    SidebarComponent,
    SidebarFooterComponent,
    SidebarHeaderComponent,
    SidebarNavComponent,
    SidebarToggleDirective,
    SidebarTogglerDirective,
    ToastHostComponent,
  ],
})
export class DefaultLayoutComponent {
  readonly #session = inject(SessionStore);
  readonly #router = inject(Router);

  readonly sessionStatus = this.#session.status;
  readonly sessionError = this.#session.error;
  readonly navItems = computed(() => buildNav(navItems, this.#session.permissions()));

  /** Carrega a sessão de novo e refaz a navegação, para as guardas conferirem a rota com as permissões carregadas. */
  async retrySession(): Promise<void> {
    await this.#session.load();
    if (this.#session.status() === 'ready') {
      await this.#router.navigateByUrl(this.#router.url, { onSameUrlNavigation: 'reload' });
    }
  }

  signInAgain(): void {
    void this.#router.navigate(['/login'], {
      queryParams: { expired: 1, returnUrl: this.#router.url },
    });
  }
}
