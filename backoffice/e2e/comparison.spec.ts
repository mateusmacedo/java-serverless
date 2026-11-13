import { expect, test } from '@playwright/test';

import { VERSION_1 } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { heterogeneousOffers } from './support/offers';

test.describe('comparação de ofertas (FE07)', () => {
  test('destaca limites e franquias diferentes, e ausência não é zero (FT14)', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-partial-offer', 'oferta da alpha em QUOTING');
    api.on('GET', `${VERSION_1}/offers`, { status: 200, body: heterogeneousOffers() });

    await page.goto(VERSION_1.replace('/v1', ''));
    await page.getByRole('region', { name: 'Alpha Seguros' }).getByLabel('Comparar').check();
    await page.getByRole('region', { name: 'Beta Seguros' }).getByLabel('Comparar').check();
    await page.getByRole('button', { name: 'Comparar selecionadas (2/3)' }).click();

    await expect(page).toHaveURL(/\/compare\?offers=/);
    const table = page.getByRole('table', { name: 'Comparação das ofertas escolhidas' });
    await expect(table).toBeVisible();
    await expect(table.getByRole('row', { name: /Colisão/ }).first()).toContainText('Diferente');
    await expect(table.getByRole('cell', { name: 'Não incluída' })).toBeVisible();
    await expect(table.getByRole('cell', { name: 'Não informado' }).first()).toBeVisible();
    await expect(table.getByText(/melhor oferta/i)).toHaveCount(0);
    api.assertNoUnexpected();
  });

  test('não compara mais de três ofertas', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-partial-offer', 'oferta da alpha em QUOTING');
    api.on('GET', `${VERSION_1}/offers`, { status: 200, body: heterogeneousOffers() });

    await page.goto(`${VERSION_1.replace('/v1', '')}/compare?offers=a,b,c,d`);

    await expect(
      page.getByText('Escolha pelo menos duas ofertas da cotação para comparar.'),
    ).toBeVisible();
  });
});
