import { expect, type Page, test } from '@playwright/test';

import { fixture, fixtureBody, VERSION_1 } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { expectToast } from './support/session';

const SELECTION = `${VERSION_1}/selection`;
const OFFER_ID = 'of_0f1e2d3c4b5a69788796a5b4c3d2e1f0';

async function attachQuoting(
  page: Page,
): Promise<{ api: MockApi; after: { selected: boolean; expired: boolean } }> {
  const api = await MockApi.attach(page, { signedIn: true });
  const after = { selected: false, expired: false };
  api.on('GET', VERSION_1, () => {
    if (after.selected) {
      return fixture('selection', 'versão com seleção');
    }
    return after.expired
      ? fixture('offer-expiration', 'sem SELECT_OFFER')
      : fixture('quote-partial-offer', 'oferta da alpha em QUOTING');
  });
  api.on('GET', `${VERSION_1}/offers`, () => {
    if (after.selected) {
      return fixture('selection', 'ofertas depois da seleção');
    }
    return after.expired
      ? fixture('offer-expiration', 'oferta vencida')
      : fixture('quote-partial-offer', 'ofertas parciais');
  });
  return { api, after };
}

async function openSelection(page: Page): Promise<void> {
  await page.goto(VERSION_1.replace('/v1', ''));
  await page.getByRole('button', { name: 'Selecionar oferta da Alpha Seguros' }).click();
  await expect(page.getByRole('button', { name: 'Registrar intenção de proposta' })).toBeEnabled();
}

test.describe('seleção de oferta (FE09)', () => {
  test('durante QUOTING, registra a intenção só com a resposta do servidor (FT11)', async ({
    page,
  }) => {
    const { api, after } = await attachQuoting(page);
    api.on('POST', SELECTION, () => {
      after.selected = true;
      return fixture('selection', 'seleção registrada');
    });

    await openSelection(page);
    const readsBeforeConfirm = api.calls('GET', VERSION_1).length;
    expect(readsBeforeConfirm).toBeGreaterThanOrEqual(2);
    await expect(
      page.getByText('Nenhuma proposta é enviada e o seguro não é contratado.'),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Registrar intenção de proposta' }).click();

    await expectToast(page, 'Intenção de proposta registrada.');
    await expect(page.getByText('Intenção de proposta registrada', { exact: true })).toBeVisible();
    await expect(page.getByText('Selecionada', { exact: true })).toBeVisible();
    await expect(page.getByText(/seguro contratado/i)).toHaveCount(0);
    const [post] = api.calls('POST', SELECTION);
    expect(post.body).toEqual({ offerId: OFFER_ID });
    expect(post.headers['idempotency-key']).toBeTruthy();
  });

  test('a oferta que expira entre a leitura e o envio mostra o 409, sem confirmação falsa (FT10)', async ({
    page,
  }) => {
    const { api, after } = await attachQuoting(page);
    api.on('POST', SELECTION, () => {
      after.expired = true;
      return fixture('offer-expiration', 'seleção recusada');
    });

    await openSelection(page);
    await page.getByRole('button', { name: 'Registrar intenção de proposta' }).click();

    await expect(
      page.getByText('A oferta expirou antes da confirmação.', { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByText('Oferta expirada: a oferta não pode mais ser selecionada.'),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Registrar intenção de proposta' }),
    ).toBeDisabled();
    await expect(page.locator('c-toast')).toHaveCount(0);
  });

  test('a seleção concorrente e a versão substituída são tratadas pelo código do contrato (FT17)', async ({
    page,
  }) => {
    const { api } = await attachQuoting(page);
    api.on('POST', SELECTION, fixture('conflicts', 'troca de oferta'));

    await openSelection(page);
    await page.getByRole('button', { name: 'Registrar intenção de proposta' }).click();
    await expect(
      page.getByText('A cotação já tem outra oferta selecionada, e a seleção é definitiva.', {
        exact: false,
      }),
    ).toBeVisible();

    await page.getByRole('button', { name: 'Cancelar' }).click();
    api.on('POST', SELECTION, fixture('conflicts', 'seleção na versão substituída'));
    await page.getByRole('button', { name: 'Selecionar oferta da Alpha Seguros' }).click();
    await page.getByRole('button', { name: 'Registrar intenção de proposta' }).click();
    await expect(
      page.getByText('Esta versão foi substituída por uma mais nova.', { exact: false }),
    ).toBeVisible();
  });

  test('sem a resposta, a verificação reenvia a mesma seleção e não presume falha', async ({
    page,
  }) => {
    const { api, after } = await attachQuoting(page);
    api.on('POST', SELECTION, () => {
      after.selected = true;
      return fixture('selection', 'reenvio com a mesma chave');
    });
    api.once('POST', SELECTION, 'abort');

    await openSelection(page);
    await page.getByRole('button', { name: 'Registrar intenção de proposta' }).click();
    await expect(
      page.getByText('A seleção pode ter sido registrada. Não presuma que não foi', {
        exact: false,
      }),
    ).toBeVisible();
    await page.getByRole('button', { name: 'Verificar seleção' }).click();

    await expectToast(page, 'Intenção de proposta registrada.');
    const [first, retry] = api.calls('POST', SELECTION);
    expect(retry.headers['idempotency-key']).toBe(first.headers['idempotency-key']);
    expect(retry.body).toEqual(first.body);
  });

  test('sem offers:select, a seleção não aparece', async ({ page }) => {
    const api = await MockApi.attach(page, {
      signedIn: true,
      me: { ...fixtureBody('me', 'corretor'), permissions: ['quotes:read'] },
    });
    api.fixture('GET', VERSION_1, 'quote-partial-offer', 'oferta da alpha em QUOTING');
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));

    await expect(page.getByRole('region', { name: 'Alpha Seguros' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Selecionar oferta/ })).toHaveCount(0);
  });
});
