import { expect, type Page, test } from '@playwright/test';

import { fixture, QUOTE_ID } from './support/fixtures';
import { MockApi } from './support/mock-api';

async function fillQuote(page: Page): Promise<void> {
  await page
    .getByLabel('Proponente', { exact: true })
    .selectOption({ label: 'Ana Lima (sintética)' });
  await page.getByLabel('Veículo', { exact: true }).selectOption({ label: 'Hatch 1.0 2021' });
  await page.getByLabel('Condutor principal').selectOption({ label: 'Ana Lima' });
  await page.getByLabel('Particular').check();
  await page.getByLabel('Perfil de cobertura').selectOption({ label: 'Completa' });
  await page.getByLabel('Início de vigência desejado').fill('2026-12-01');
}

test.describe('nova cotação (FE03, FE04 e FE14)', () => {
  test('os erros ficam nos campos, o foco vai ao primeiro e nenhuma chamada sai (FT02)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });

    await page.goto('/quotes/new');
    await expect(page.getByLabel('Proponente', { exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Revisar solicitação' }).click();

    await expect(page.getByLabel('Proponente', { exact: true })).toBeFocused();
    await expect(page.getByText('Preencha este campo.')).toHaveCount(6);
    expect(api.calls('POST', '/v1/quotes')).toHaveLength(0);
  });

  test('duplo clique é uma intenção, e o timeout recupera a mesma intenção, inclusive depois de recarregar (FT03)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.on('POST', '/v1/quotes', fixture('quote-progress', 'aceitação durável'));
    api.once('POST', '/v1/quotes', 'abort');
    api.fixture('GET', `/v1/quotes/${QUOTE_ID}/versions/1`, 'quote-progress', 'recebida');

    await page.goto('/quotes/new');
    await fillQuote(page);
    await page.getByRole('button', { name: 'Revisar solicitação' }).click();
    await expect(page.getByText('Não é uma contratação')).toBeVisible();
    await page.getByRole('button', { name: 'Enviar solicitação' }).dblclick();

    await expect(page.getByText('Não recebemos a confirmação do servidor.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Voltar e editar' })).toBeDisabled();
    expect(api.calls('POST', '/v1/quotes')).toHaveLength(1);

    await page.reload();
    await expect(page.getByText('Não recebemos a confirmação do servidor.')).toBeVisible();
    await page.getByRole('button', { name: 'Verificar envio' }).click();

    await expect(page).toHaveURL(new RegExp(`/quotes/${QUOTE_ID}/versions/1$`));
    const [first, retry] = api.calls('POST', '/v1/quotes');
    expect(retry.headers['idempotency-key']).toBe(first.headers['idempotency-key']);
    expect(retry.body).toEqual(first.body);
    expect(first.body).toEqual({
      product: 'AUTO',
      applicantRef: 'customer-acme-ana',
      risk: {
        vehicleRef: 'vehicle-hatch-2021',
        usage: 'PERSONAL',
        principalDriverRef: 'driver-acme-ana',
      },
      coverageProfile: 'COMPREHENSIVE',
      desiredStartDate: '2026-12-01',
    });
  });

  test('o 400 aponta os campos pelo caminho do contrato', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('POST', '/v1/quotes', 'errors', 'validação');

    await page.goto('/quotes/new');
    await fillQuote(page);
    await page.getByRole('button', { name: 'Revisar solicitação' }).click();
    await page.getByRole('button', { name: 'Enviar solicitação' }).click();

    await expect(page.getByText('Use uma data válida no formato YYYY-MM-DD.')).toBeVisible();
    await expect(page.getByText('Use PERSONAL ou COMMERCIAL.')).toBeVisible();
    await expect(page.getByText('Código para o suporte')).toBeVisible();
  });

  test('o catálogo vazio explica a falta de opções e oferece atualizar', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.on('GET', '/v1/catalog/drivers', { status: 200, body: { items: [], nextCursor: null } });

    await page.goto('/quotes/new');

    await expect(
      page.getByText('Nenhuma opção disponível no catálogo da sua organização.'),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Atualizar' }).click();
    await expect.poll(() => api.calls('GET', '/v1/catalog/drivers').length).toBe(2);
  });
});
