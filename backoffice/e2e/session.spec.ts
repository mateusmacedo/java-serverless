import { expect, test } from '@playwright/test';

import { fixture } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { expectToast, signIn } from './support/session';

test.describe('sessão e permissões (FT12 e FT13)', () => {
  test('o login volta ao endereço pedido e mostra o usuário', async ({ page }) => {
    const api = await MockApi.attach(page);

    await page.goto('/quotes?outcome=COMPLETED');
    await expect(page).toHaveURL(/\/login\?returnUrl=/);
    await signIn(page);

    await expect(page).toHaveURL(/\/quotes\?outcome=COMPLETED$/);
    await expect(page.getByRole('heading', { name: 'Cotações', level: 1 })).toBeVisible();
    expect(api.cognitoCalls[0]).toMatchObject({
      target: 'InitiateAuth',
      body: { AuthFlow: 'USER_PASSWORD_AUTH', ClientId: 'e2e-client' },
    });
    api.assertNoUnexpected();
  });

  test('a senha errada fica no login, sem revelar se o usuário existe', async ({ page }) => {
    await MockApi.attach(page);

    await page.goto('/login');
    await signIn(page, 'corretor', 'senha-errada');

    await expect(page.getByRole('alert')).toHaveText('Usuário ou senha incorretos.');
    await expect(page).toHaveURL(/\/login/);
  });

  test('o 401 sem renovação leva ao login com o aviso e o retorno', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.refreshFails = true;
    api.on('GET', '/v1/quotes', fixture('errors', 'sessão inválida'));

    await page.goto('/quotes');

    await expect(page).toHaveURL(/\/login\?expired=1&returnUrl=%2Fquotes/);
    await expect(page.getByText('Sua sessão expirou. Entre de novo para continuar.')).toBeVisible();
  });

  test('o operador não vê as cotações, e o acesso direto é recusado', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true, me: 'operador' });
    api.fixture('GET', '/v1/operations/issues', 'operations', 'ocorrências abertas');

    await page.goto('/quotes');

    await expect(page).toHaveURL(/\/operations$/);
    await expectToast(page, 'Você não tem acesso a esta área.');
    const menu = page.locator('c-sidebar-nav');
    await expect(menu.getByText('Ocorrências')).toBeVisible();
    await expect(menu.getByText('Cotações')).toHaveCount(0);
  });

  test('a saída apaga a sessão da aba, e o próximo usuário não herda nada (FT13)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page);
    api.on('GET', '/v1/operations/issues', fixture('operations', 'ocorrências abertas'));

    await page.goto('/login');
    await signIn(page);
    await expect(page).toHaveURL(/\/quotes$/);
    await page.getByRole('button', { name: 'Abrir o menu do usuário' }).click();
    await page.getByRole('button', { name: 'Sair' }).click();

    await expect(page).toHaveURL(/\/login$/);
    const keys = await page.evaluate(() =>
      Object.keys(sessionStorage).filter((key) => key.startsWith('backoffice.')),
    );
    expect(keys).toEqual([]);
    expect(api.cognitoCalls.map((call) => call.target)).toContain('RevokeToken');

    api.me = 'operador';
    await signIn(page, 'operador');
    await expect(page).toHaveURL(/\/operations$/);
    await expect(page.locator('c-sidebar-nav').getByText('Cotações')).toHaveCount(0);
  });
});
