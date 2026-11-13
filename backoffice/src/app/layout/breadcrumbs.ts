import type { ActivatedRouteSnapshot } from '@angular/router';

/** Um passo do breadcrumb: o `data.title` da rota e o endereço dela. */
export interface Crumb {
  readonly label: string;
  readonly url: string;
}

/**
 * Trilha do breadcrumb da rota ativa: um passo por rota que declara o próprio `data.title`, da raiz à folha. A rota
 * que só herda o título do pai não repete o passo, e dois títulos iguais seguidos viram um só.
 */
export function breadcrumbs(root: ActivatedRouteSnapshot): Crumb[] {
  const crumbs: Crumb[] = [];
  const segments: string[] = [];
  for (let route: ActivatedRouteSnapshot | null = root; route; route = route.firstChild) {
    segments.push(...route.url.map((segment) => segment.path));
    const title: unknown = route.routeConfig?.data?.['title'];
    if (typeof title === 'string' && title && crumbs.at(-1)?.label !== title) {
      crumbs.push({ label: title, url: `/${segments.join('/')}` });
    }
  }
  return crumbs;
}
