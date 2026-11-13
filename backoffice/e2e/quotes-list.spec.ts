import { expect, test } from '@playwright/test';

import { fixture } from './support/fixtures';
import { MockApi } from './support/mock-api';

test.describe('lista de cotações (FE02)', () => {
  test('a lista vazia mostra a ação de criar, sem erro (FT01)', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', '/v1/quotes', 'quotes-list', 'lista vazia');

    await page.goto('/quotes');

    await expect(page.getByText('Nenhuma cotação ainda.')).toBeVisible();
    await expect(
      page.getByRole('main').getByRole('link', { name: 'Nova cotação' }).last(),
    ).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    api.assertNoUnexpected();
  });

  test('o cursor e os filtros ficam na URL, e um filtro novo recomeça a paginação', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.on('GET', '/v1/quotes', (request) =>
      request.query.get('cursor')
        ? fixture('quotes-list', 'última página')
        : fixture('quotes-list', 'primeira página'),
    );

    await page.goto('/quotes');
    await expect(page.getByRole('row')).toHaveCount(3);
    await page.getByRole('button', { name: 'Próxima' }).click();

    await expect(page).toHaveURL(/cursor=/);
    await expect(page.getByRole('row')).toHaveCount(2);
    const cursor = new URL(page.url()).searchParams.get('cursor');
    expect(api.calls('GET', '/v1/quotes').at(-1)?.query.get('cursor')).toBe(cursor);

    await page.getByLabel('Resultado').selectOption({ label: 'Consultas concluídas' });
    await page.getByLabel('Criada de').fill('2026-11-12');
    await page.getByLabel('Criada até').fill('2026-11-12');
    await page.getByRole('button', { name: 'Filtrar' }).click();

    await expect(page).toHaveURL(/outcome=COMPLETED/);
    expect(new URL(page.url()).searchParams.get('cursor')).toBeNull();
    const last = api.calls('GET', '/v1/quotes').at(-1)!;
    expect(last.query.get('outcome')).toBe('COMPLETED');
    expect(last.query.get('createdFrom')).toBe('2026-11-12T03:00:00.000Z');
    expect(last.query.get('createdTo')).toBe('2026-11-13T02:59:59.999Z');
    expect(last.query.get('cursor')).toBeNull();

    await page.reload();
    await expect(page.getByLabel('Resultado')).toHaveValue('COMPLETED');
  });

  test('o período invertido é recusado antes de chamar a API', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });

    await page.goto('/quotes');
    await expect(page.getByRole('row')).toHaveCount(3);
    const before = api.calls('GET', '/v1/quotes').length;
    await page.getByLabel('Criada de').fill('2026-11-12');
    await page.getByLabel('Criada até').fill('2026-11-01');
    await page.getByRole('button', { name: 'Filtrar' }).click();

    await expect(page.getByText('A data final não pode ser anterior à inicial.')).toBeVisible();
    expect(api.calls('GET', '/v1/quotes')).toHaveLength(before);
  });
});
