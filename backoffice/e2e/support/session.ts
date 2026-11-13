import { expect, type Page } from '@playwright/test';

/** Entra pela tela de login com o usuário de teste; o Cognito simulado aceita qualquer senha, menos `senha-errada`. */
export async function signIn(
  page: Page,
  username = 'corretor',
  password = 'Senha#Segura1',
): Promise<void> {
  await page.getByLabel('Usuário').fill(username);
  await page.getByLabel('Senha').fill(password);
  await page.getByRole('button', { name: 'Entrar' }).click();
}

/** Simula a aba ficando oculta ou voltando a ficar visível, como ao trocar de aba no navegador. */
export async function setTabHidden(page: Page, hidden: boolean): Promise<void> {
  await page.evaluate((value) => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => value });
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => (value ? 'hidden' : 'visible'),
    });
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);
}

/** Toast do layout com o texto, que confirma uma ação. */
export async function expectToast(page: Page, text: string | RegExp): Promise<void> {
  await expect(page.locator('c-toast').filter({ hasText: text })).toBeVisible();
}
