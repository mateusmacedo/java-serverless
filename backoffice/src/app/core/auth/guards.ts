import { inject } from '@angular/core';
import { type CanActivateFn, type CanMatchFn, Router } from '@angular/router';

import type { Permission } from '../../shared/api';
import { ToastService } from '../../shared/ui/toast/toast.service';
import { AuthSession } from './auth-session';
import { safeReturnUrl } from './browser-location';
import { SessionStore } from './session-store';

export const NO_ACCESS_MESSAGE = 'Você não tem acesso a esta área.';

/** Sem login, leva à tela de login com o endereço pedido para a volta. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthSession);
  return auth.isAuthenticated()
    ? true
    : inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

/** Com login, a tela de login leva direto ao endereço de retorno. */
export const guestGuard: CanActivateFn = (route) => {
  const auth = inject(AuthSession);
  return auth.isAuthenticated()
    ? inject(Router).parseUrl(safeReturnUrl(route.queryParamMap.get('returnUrl')))
    : true;
};

/**
 * Libera o grupo de rotas para quem tem a permissão, segundo `GET /v1/me`; sem ela, volta ao início com um aviso. Vai
 * no `canMatch`, para a feature nem ser baixada. O servidor continua recusando o recurso a quem não tem a permissão.
 */
export function permissionGuard(permission: Permission): CanMatchFn {
  return async () => {
    const auth = inject(AuthSession);
    const session = inject(SessionStore);
    const router = inject(Router);
    const toasts = inject(ToastService);
    if (!auth.isAuthenticated()) {
      return true;
    }
    await session.ensureLoaded();
    // WHY: sem a sessão, o layout mostra o erro no lugar da tela, e o "Tentar de novo" refaz a navegação, que passa
    // por esta guarda outra vez com as permissões já carregadas.
    const status = session.status();
    if (status === 'error' || status === 'expired' || session.can(permission)) {
      return true;
    }
    toasts.warning(NO_ACCESS_MESSAGE, 'Acesso');
    return router.parseUrl('/');
  };
}

/** O início leva à primeira área que a sessão libera; sem nenhuma, mostra o aviso de acesso. */
export const homeGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);
  await session.ensureLoaded();
  if (session.status() !== 'ready') {
    return true;
  }
  if (session.can('quotes:read')) {
    return router.parseUrl('/quotes');
  }
  return session.can('operations:read') ? router.parseUrl('/operations') : true;
};
