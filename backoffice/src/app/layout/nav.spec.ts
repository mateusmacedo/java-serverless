import type { Permission } from '../shared/api';
import { buildNav, navItems } from './nav';

describe('menu por permissão (FE01)', () => {
  it('mostra ao corretor só as cotações, sem o grupo vazio de operação', () => {
    const items = buildNav(
      navItems,
      new Set<Permission>(['quotes:read', 'quotes:write', 'offers:select']),
    );

    expect(items.map((item) => item.name)).toEqual(['Comercial', 'Cotações']);
  });

  it('mostra ao operador só as ocorrências', () => {
    const items = buildNav(
      navItems,
      new Set<Permission>(['operations:read', 'operations:reconcile']),
    );

    expect(items.map((item) => item.name)).toEqual(['Operação técnica', 'Ocorrências']);
  });

  it('sem permissão, o menu fica vazio', () => {
    expect(buildNav(navItems, new Set())).toEqual([]);
  });
});
