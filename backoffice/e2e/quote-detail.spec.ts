import { expect, test } from '@playwright/test';

import { fixtureBody, VERSION_1 } from './support/fixtures';
import { MockApi } from './support/mock-api';
import { setTabHidden } from './support/session';

const QUOTING = 'Consultando seguradoras — resultados podem aparecer gradualmente.';

function quoting(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ...fixtureBody('quote-partial-offer', 'oferta da alpha em QUOTING'),
    retryAfterSeconds: 1,
    ...overrides,
  };
}

test.describe('acompanhamento da cotação (FE05, FE06 e FE10)', () => {
  test('a oferta da alpha aparece enquanto a beta segue em consulta (FT04)', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.on('GET', VERSION_1, { status: 200, body: quoting() });
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));

    await expect(page.getByRole('region', { name: 'Alpha Seguros' })).toBeVisible();
    await expect(page.getByText(QUOTING)).toBeVisible();
    await expect(
      page.getByText('Mais resultados podem chegar enquanto as outras seguradoras respondem.'),
    ).toBeVisible();
    const insurers = page.getByRole('list', { name: 'Resultados por seguradora' });
    await expect(insurers.getByText('Em consulta')).toBeVisible();
    await expect(insurers.getByText('Oferta disponível')).toBeVisible();
    await expect(page.getByText('%')).toHaveCount(0);
    api.assertNoUnexpected();
  });

  test('recusa comercial e indisponibilidade técnica têm textos e estados distintos (FT05)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-declined', 'todas recusaram');

    await page.goto(VERSION_1.replace('/v1', ''));
    await expect(
      page.getByText('Nenhuma seguradora apresentou oferta para este risco.'),
    ).toBeVisible();
    await expect(
      page.getByText('Todas as seguradoras consultadas recusaram o risco.'),
    ).toBeVisible();
    await expect(page.getByText('Falha técnica')).toHaveCount(0);

    api.fixture('GET', VERSION_1, 'quote-unavailable', 'seguradoras indisponíveis');
    await page.reload();
    await expect(
      page.getByText('Não foi possível concluir as consultas por um problema técnico', {
        exact: false,
      }),
    ).toBeVisible();
    await expect(
      page.getByText('As seguradoras ficaram indisponíveis ou não responderam no prazo.'),
    ).toBeVisible();
    await expect(page.getByText('Nenhuma seguradora apresentou oferta')).toHaveCount(0);
  });

  test('sem rede, as ofertas continuam na tela com o horário e o aviso (FT06)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    let offline = false;
    api.on('GET', VERSION_1, () => (offline ? 'abort' : { status: 200, body: quoting() }));
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));
    await expect(page.getByRole('region', { name: 'Alpha Seguros' })).toBeVisible();
    offline = true;

    const stale = page.getByRole('alert').filter({ hasText: 'Não foi possível atualizar agora.' });
    await expect(stale).toBeVisible();
    await expect(stale).toContainText('Mostrando os dados recebidos às');
    await expect(page.getByRole('region', { name: 'Alpha Seguros' })).toBeVisible();
    expect(api.calls('POST', /.*/)).toHaveLength(0);

    offline = false;
    await stale.getByRole('button', { name: 'Atualizar' }).click();
    await expect(stale).toHaveCount(0);
  });

  test('uma resposta com revision menor não regride a tela (FT07)', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    let calls = 0;
    const older = { ...fixtureBody('quote-progress', 'recebida'), retryAfterSeconds: 1 };
    api.on('GET', VERSION_1, () =>
      ++calls === 1 ? { status: 200, body: quoting() } : { status: 200, body: older },
    );
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));
    await expect(page.getByText(QUOTING)).toBeVisible();
    await expect.poll(() => calls, { timeout: 10_000 }).toBeGreaterThanOrEqual(3);

    await expect(page.getByText(QUOTING)).toBeVisible();
    await expect(page.getByText('Solicitação recebida — protocolo disponível.')).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Alpha Seguros' })).toBeVisible();
  });

  test('com a aba oculta, o polling para; ao voltar, lê na hora, sem duplicar o laço (FT08)', async ({
    page,
  }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    let calls = 0;
    api.on('GET', VERSION_1, () => {
      calls++;
      return { status: 200, body: quoting() };
    });
    api.fixture('GET', `${VERSION_1}/offers`, 'quote-partial-offer', 'ofertas parciais');

    await page.goto(VERSION_1.replace('/v1', ''));
    await expect.poll(() => calls).toBeGreaterThanOrEqual(2);
    await setTabHidden(page, true);
    const hiddenAt = calls;
    // eslint-disable-next-line playwright/no-wait-for-timeout -- WHY: a asserção é a ausência de chamadas num intervalo.
    await page.waitForTimeout(2_500);
    expect(calls).toBe(hiddenAt);

    await setTabHidden(page, false);
    await expect.poll(() => calls).toBe(hiddenAt + 1);
    const visibleAt = calls;
    // eslint-disable-next-line playwright/no-wait-for-timeout -- WHY: mede o ritmo do laço depois da volta.
    await page.waitForTimeout(2_200);
    expect(calls - visibleAt).toBeLessThanOrEqual(3);
  });

  test('o polling para no resultado final', async ({ page }) => {
    const api = await MockApi.attach(page, { signedIn: true });
    api.fixture('GET', VERSION_1, 'quote-declined', 'todas recusaram');

    await page.goto(VERSION_1.replace('/v1', ''));
    await expect(page.getByText('Nenhuma oferta nesta versão.')).toBeVisible();
    // eslint-disable-next-line playwright/no-wait-for-timeout -- WHY: a asserção é a ausência de chamadas num intervalo.
    await page.waitForTimeout(2_000);

    expect(api.calls('GET', VERSION_1)).toHaveLength(1);
  });
});
