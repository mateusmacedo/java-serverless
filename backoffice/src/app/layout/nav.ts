import type { INavData } from '@coreui/angular';

import type { Permission } from '../shared/api';

/** Item do menu com a permissão exigida; o menu lateral do CoreUI ignora `permission`. */
export interface AppNavItem extends INavData {
  readonly permission?: Permission;
}

/**
 * Menu do painel. Os grupos são só organização visual: os títulos não exigem nada e somem quando nenhum item do grupo
 * sobra para o usuário.
 */
export const navItems: readonly AppNavItem[] = [
  { name: 'Comercial', title: true },
  {
    name: 'Cotações',
    url: '/quotes',
    iconComponent: { name: 'cil-list' },
    permission: 'quotes:read',
  },
  { name: 'Operação técnica', title: true },
  {
    name: 'Ocorrências',
    url: '/operations',
    iconComponent: { name: 'cil-warning' },
    permission: 'operations:read',
  },
];

/** Tira do menu os itens que a sessão não libera e os títulos dos grupos que ficaram vazios. */
export function buildNav(
  items: readonly AppNavItem[],
  permissions: ReadonlySet<Permission>,
): AppNavItem[] {
  const visible = items.filter(
    (item) => item.title || !item.permission || permissions.has(item.permission),
  );
  return visible.filter((item, index) => !item.title || hasEntryBeforeNextTitle(visible, index));
}

function hasEntryBeforeNextTitle(items: readonly AppNavItem[], titleIndex: number): boolean {
  const next = items[titleIndex + 1];
  return !!next && !next.title;
}
