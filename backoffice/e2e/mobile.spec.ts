import { expect, type Page, test } from '@playwright/test';

import { fixture, VERSION_1 } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { heterogeneousOffers } from './support/offers';

async function hasNoHorizontalScroll(page: Page): Promise<boolean> {
  return page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
}

test.describe('tela estreita e teclado (FE12 e FT15)', () => {
  test('a comparação vira cartões sem perder dados e sem rolagem horizontal', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-partial-offer', 'oferta da alpha em QUOTING');
    const offers = heterogeneousOffers();
    api.on('GET', `${VERSION_1}/offers`, { status: 200, body: offers });

    await page.goto(
      `${VERSION_1.replace('/v1', '')}/compare?offers=${offers.items.map((item) => item.id).join(',')}`,
    );

    await expect(page.getByRole('table')).toBeHidden();
    const beta = page.getByRole('region', { name: 'Beta Seguros' });
    await expect(beta).toBeVisible();
    await expect(beta.getByText('Não incluída')).toBeVisible();
    await expect(beta.getByText('Diferente').first()).toBeVisible();
    expect(await hasNoHorizontalScroll(page)).toBe(true);
  });

  test('o formulário cabe na tela e funciona só com o teclado', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });

    await page.goto('/quotes/new');
    await expect(page.getByLabel('Proponente', { exact: true })).toBeEnabled();
    expect(await hasNoHorizontalScroll(page)).toBe(true);

    await page.getByLabel('Proponente', { exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('Buscar veículo')).toBeFocused();
    await page.getByRole('button', { name: 'Revisar solicitação' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByLabel('Proponente', { exact: true })).toBeFocused();
    expect(api.calls('POST', '/v1/quotes')).toHaveLength(0);
  });

  test('o modal de seleção abre e fecha pelo teclado', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.on('GET', VERSION_1, fixture('quote-partial-offer', 'oferta da alpha em QUOTING'));
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));
    const select = page.getByRole('button', { name: 'Selecionar oferta da Alpha Seguros' });
    await select.focus();
    await page.keyboard.press('Enter');
    await expect(
      page.getByRole('button', { name: 'Registrar intenção de proposta' }),
    ).toBeEnabled();
    expect(await hasNoHorizontalScroll(page)).toBe(true);

    await page.keyboard.press('Escape');
    await expect(page.getByRole('button', { name: 'Registrar intenção de proposta' })).toBeHidden();
    expect(api.calls('POST', /selection$/)).toHaveLength(0);
  });
});
