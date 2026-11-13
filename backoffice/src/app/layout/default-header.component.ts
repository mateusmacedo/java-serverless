import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import {
  AvatarComponent,
  BreadcrumbComponent,
  BreadcrumbItemComponent,
  ColorModeService,
  ContainerComponent,
  DropdownComponent,
  DropdownHeaderDirective,
  DropdownItemDirective,
  DropdownMenuDirective,
  DropdownToggleDirective,
  HeaderComponent,
  HeaderNavComponent,
  HeaderTogglerDirective,
  SidebarToggleDirective,
} from '@coreui/angular';
import { IconDirective } from '@coreui/icons-angular';
import { filter, map } from 'rxjs';

import { AuthSession } from '../core/auth/auth-session';
import { SessionStore } from '../core/auth/session-store';
import { breadcrumbs } from './breadcrumbs';

/**
 * Cabeçalho do painel, como o do template grátis do CoreUI: alterna o menu lateral, escolhe o tema (claro, escuro ou
 * automático), mostra o usuário e a organização com a saída e o breadcrumb da rota.
 */
@Component({
  selector: 'app-default-header',
  templateUrl: './default-header.component.html',
  imports: [
    AvatarComponent,
    BreadcrumbComponent,
    BreadcrumbItemComponent,
    ContainerComponent,
    DropdownComponent,
    DropdownHeaderDirective,
    DropdownItemDirective,
    DropdownMenuDirective,
    DropdownToggleDirective,
    HeaderNavComponent,
    HeaderTogglerDirective,
    IconDirective,
    SidebarToggleDirective,
  ],
})
export class DefaultHeaderComponent extends HeaderComponent {
  readonly #colorModeService = inject(ColorModeService);
  readonly #router = inject(Router);
  readonly #auth = inject(AuthSession);

  readonly sidebarId = input('sidebar1');
  readonly colorMode = this.#colorModeService.colorMode;
  readonly colorModes = [
    { name: 'light', text: 'Claro', icon: 'cilSun' },
    { name: 'dark', text: 'Escuro', icon: 'cilMoon' },
    { name: 'auto', text: 'Automático', icon: 'cilContrast' },
  ] as const;
  readonly themeIcon = computed(
    () => this.colorModes.find((mode) => mode.name === this.colorMode())?.icon ?? 'cilSun',
  );
  readonly me = inject(SessionStore).me;

  /** Trilha da rota ativa, refeita a cada navegação concluída. */
  readonly crumbs = toSignal(
    this.#router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => breadcrumbs(this.#router.routerState.snapshot.root)),
    ),
    { initialValue: breadcrumbs(this.#router.routerState.snapshot.root) },
  );

  logout(): void {
    void this.#auth.logout();
  }
}
