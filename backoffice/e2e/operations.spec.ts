import { expect, test } from '@playwright/test';

import { fixture, fixtureBody } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { expectToast } from './support/session';

const RECONCILE = /^\/v1\/operations\/queries\/[^/]+\/reconcile$/;
const OPERATION = /^\/v1\/operations\/op_[^/]+$/;

test.describe('painel operacional (FE11)', () => {
  test('pede a reconciliação com motivo e acompanha o resultado sem repetir o comando (FT16)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true, me: 'operador' });
    api.fixture('GET', '/v1/operations/issues', 'operations', 'ocorrências abertas');
    api.fixture('POST', RECONCILE, 'operations', 'reconciliação aceita');
    let reads = 0;
    api.on('GET', OPERATION, () =>
      ++reads === 1 ? fixture('operations', 'na fila') : fixture('operations', 'concluída'),
    );

    await page.goto('/operations');
    const reconcile = page.getByRole('button', { name: /^Reconciliar/ });
    await expect(reconcile).toHaveCount(1);
    await reconcile.click();

    await page.getByRole('button', { name: 'Pedir reconciliação' }).click();
    await expect(page.getByText('Preencha este campo.')).toBeVisible();
    expect(api.calls('POST', RECONCILE)).toHaveLength(0);

    await page
      .getByLabel('Motivo')
      .fill('Consulta UNKNOWN há mais de 10 minutos; conferir com o fornecedor.');
    await page.getByRole('button', { name: 'Pedir reconciliação' }).click();

    await expectToast(page, 'o pedido aceito não significa consulta recuperada');
    const tracked = page.getByRole('heading', { name: 'Reconciliações pedidas nesta sessão' });
    await expect(tracked).toBeVisible();
    await expect(page.getByText('Concluída com sucesso')).toBeVisible({ timeout: 10_000 });
    const [post] = api.calls('POST', RECONCILE);
    expect(post.body).toEqual({
      reason: 'Consulta UNKNOWN há mais de 10 minutos; conferir com o fornecedor.',
    });
    expect(post.headers['idempotency-key']).toBeTruthy();
    expect(api.calls('POST', RECONCILE)).toHaveLength(1);
    api.assertNoUnexpected();
  });

  test('mostra a falha da reconciliação como resultado, não como erro de tela', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true, me: 'operador' });
    api.fixture('GET', '/v1/operations/issues', 'operations', 'ocorrências abertas');
    api.fixture('POST', RECONCILE, 'operations', 'reconciliação aceita');
    api.fixture('GET', OPERATION, 'operations', 'concluída sem sucesso');

    await page.goto('/operations');
    await page.getByRole('button', { name: /^Reconciliar/ }).click();
    await page.getByLabel('Motivo').fill('Conferir a consulta sem resposta.');
    await page.getByRole('button', { name: 'Pedir reconciliação' }).click();

    await expect(page.getByText('Concluída sem sucesso')).toBeVisible();
    await expect(
      page.getByText('O fornecedor não tem API de situação; a consulta continua UNKNOWN'),
    ).toBeVisible();
  });

  test('sem operations:reconcile, a ação não aparece mesmo que o servidor a ofereça', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, {
      signedIn: true,
      me: { ...fixtureBody('me', 'operador'), permissions: ['operations:read'] },
    });
    api.fixture('GET', '/v1/operations/issues', 'operations', 'ocorrências abertas');

    await page.goto('/operations');

    await expect(
      page.getByRole('cell', { name: 'Consulta sem resposta', exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /^Reconciliar/ })).toHaveCount(0);
  });

  test('os filtros ficam na URL e vão à API', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true, me: 'operador' });
    api.fixture('GET', '/v1/operations/issues', 'operations', 'ocorrências abertas');

    await page.goto('/operations');
    await page.getByLabel('Tipo').selectOption({ label: 'Versão parada' });

    await expect(page).toHaveURL(/type=STUCK_VERSION/);
    await expect
      .poll(() => api.calls('GET', '/v1/operations/issues').at(-1)?.query.get('type'))
      .toBe('STUCK_VERSION');
  });
});
