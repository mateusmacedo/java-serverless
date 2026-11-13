import type { Routes } from '@angular/router';

import { permissionGuard } from '../../core/auth/guards';
import { leaveGuard } from '../../shared/ui/leave/leave-guard';

/**
 * Cotações, montadas em `/quotes`: a lista, a nova cotação e cada versão com a comparação e a complementação. O
 * grupo leva o título da lista, para o breadcrumb de uma versão voltar a ela.
 */
export const quotesRoutes: Routes = [
  {
    path: '',
    data: { title: 'Cotações' },
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./list/quote-list-page.component').then((m) => m.QuoteListPageComponent),
      },
      {
        path: 'new',
        canMatch: [permissionGuard('quotes:write')],
        canDeactivate: [leaveGuard],
        data: { title: 'Nova cotação' },
        loadComponent: () =>
          import('./new/new-quote-page.component').then((m) => m.NewQuotePageComponent),
      },
      {
        path: ':quoteId/versions/:version',
        data: { title: 'Cotação' },
        children: [
          {
            path: '',
            loadComponent: () =>
              import('./detail/quote-version-page.component').then(
                (m) => m.QuoteVersionPageComponent,
              ),
          },
          {
            path: 'compare',
            data: { title: 'Comparação' },
            loadComponent: () =>
              import('../offers/offer-comparison-page.component').then(
                (m) => m.OfferComparisonPageComponent,
              ),
          },
          {
            path: 'edit',
            canMatch: [permissionGuard('quotes:write')],
            canDeactivate: [leaveGuard],
            data: { title: 'Complementação' },
            loadComponent: () =>
              import('./edit/quote-edit-page.component').then((m) => m.QuoteEditPageComponent),
          },
        ],
      },
    ],
  },
];
