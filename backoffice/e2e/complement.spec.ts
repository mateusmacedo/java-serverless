import { expect, type Page, test } from '@playwright/test';

import { fixture, fixtureBody, QUOTE_ID, VERSION_1 } from './support/fixtures';
import { MockApi } from './support/mock-api';

const VERSIONS = `/v1/quotes/${QUOTE_ID}/versions`;

async function startComplement(page: Page): Promise<MockApi> {
  const api = await MockApi.attach(page, { signedIn: true });
  api.fixture('GET', VERSION_1, 'quote-needs-information', 'habilitação ausente no cadastro');
  await page.goto(VERSION_1.replace('/v1', ''));
  await expect(
    page.getByText('Data da primeira habilitação do condutor principal').first(),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Complementar dados' }).click();
  await expect(page).toHaveURL(/\/edit$/);
  await expect(
    page.getByText('Pendência apontada nesta versão: escolha outro condutor', { exact: false }),
  ).toBeVisible();
  await page.getByLabel('Condutor principal').selectOption({ label: 'Ana Lima' });
  await page.getByRole('button', { name: 'Revisar complementação' }).click();
  return api;
}

test.describe('complementação por versão (FE08)', () => {
  test('cria a versão 2 com expectedVersion e mantém a versão 1 no histórico (FT09)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-needs-information', 'habilitação ausente no cadastro');
    api.fixture('POST', VERSIONS, 'quote-needs-information', 'complementação cria a versão 2');
    api.on('GET', `${VERSIONS}/2`, {
      status: 200,
      body: { ...fixtureBody('quote-progress', 'recebida'), version: 2, currentVersion: 2 },
    });

    await page.goto(VERSION_1.replace('/v1', ''));
    await page.getByRole('link', { name: 'Complementar dados' }).click();
    await page.getByLabel('Condutor principal').selectOption({ label: 'Ana Lima' });
    await page.getByRole('button', { name: 'Revisar complementação' }).click();
    await page.getByRole('button', { name: 'Criar nova versão' }).click();

    await expect(page).toHaveURL(new RegExp(`/quotes/${QUOTE_ID}/versions/2$`));
    const [post] = api.calls('POST', VERSIONS);
    expect(post.body).toMatchObject({
      expectedVersion: 1,
      risk: { principalDriverRef: 'driver-acme-ana' },
    });
    expect(post.headers['idempotency-key']).toBeTruthy();

    api.fixture('GET', VERSION_1, 'quote-needs-information', 'versão 1 substituída');
    await page.goto(VERSION_1.replace('/v1', ''));
    await expect(page.getByText('Esta é a versão 1, substituída', { exact: false })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Abrir a versão atual' })).toHaveAttribute(
      'href',
      /versions\/2$/,
    );
    await expect(page.getByRole('link', { name: 'Complementar dados' })).toHaveCount(0);
  });

  test('o 409 de versão mantém os dados digitados e não sobrescreve o servidor', async ({
    page,
  }) => {
    const api = await startComplement(page);
    api.on('POST', VERSIONS, fixture('conflicts', 'expectedVersion desatualizado'));

    await page.getByRole('button', { name: 'Criar nova versão' }).click();

    await expect(
      page.getByText('Outra versão foi criada depois que você abriu esta tela.', { exact: false }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Criar nova versão' })).toBeDisabled();
    await page.getByRole('button', { name: 'Voltar e editar' }).click();
    await expect(page.getByLabel('Condutor principal')).toHaveValue(/driver-acme-ana|1/);
    expect(api.calls('POST', VERSIONS)).toHaveLength(1);
  });
});
