import type { Routes } from '@angular/router';

import { authGuard, guestGuard, homeGuard, permissionGuard } from './core/auth/guards';

/**
 * Rotas do PRD (§4). Cada área vem sob demanda, e a guarda de cada grupo impede até o download dela para quem a
 * sessão (`GET /v1/me`) não libera. O servidor continua sendo quem autoriza cada recurso.
 */
export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    loadComponent: () =>
      import('./views/login/login-page.component').then((m) => m.LoginPageComponent),
  },
  {
    path: '',
    loadComponent: () =>
      import('./layout/default-layout.component').then((m) => m.DefaultLayoutComponent),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        canActivate: [homeGuard],
        loadComponent: () =>
          import('./views/no-access/no-access.component').then((m) => m.NoAccessComponent),
      },
      {
        path: 'quotes',
        canMatch: [permissionGuard('quotes:read')],
        loadChildren: () => import('./features/quotes/quotes.routes').then((m) => m.quotesRoutes),
      },
      {
        path: 'operations',
        canMatch: [permissionGuard('operations:read')],
        loadChildren: () =>
          import('./features/operations/operations.routes').then((m) => m.operationsRoutes),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
