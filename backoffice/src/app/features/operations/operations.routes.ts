import type { Routes } from '@angular/router';

/** Painel operacional, montado em `/operations`. */
export const operationsRoutes: Routes = [
  {
    path: '',
    data: { title: 'Ocorrências' },
    loadComponent: () =>
      import('./operations-page.component').then((m) => m.OperationsPageComponent),
  },
];
