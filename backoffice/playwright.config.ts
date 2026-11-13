import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env['E2E_PORT'] ?? 4310);
const isCI = !!process.env['CI'];

/**
 * Jornadas do PRD (FT01–FT17) no navegador. A API e o Cognito respondem com as fixtures de `contracts/fixtures` por
 * `page.route` (e2e/support/mock-api.ts): os mocks existem só aqui, e o app em execução fala sempre com a API real.
 */
export default defineConfig({
  testDir: './e2e',
  outputDir: './test-output/e2e',
  fullyParallel: false,
  forbidOnly: isCI,
  retries: isCI ? 1 : 0,
  // WHY: um Chromium por worker, ao lado do dev server e do Floci, esgota a memória do host; E2E_WORKERS sobe o número.
  workers: Number(process.env['E2E_WORKERS'] ?? 1),
  reporter: isCI
    ? [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]]
    : 'list',
  use: {
    baseURL: `http://localhost:${PORT}`,
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `pnpm exec ng serve --port ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !isCI,
    timeout: 180_000,
    stdout: 'ignore',
  },
  projects: [
    { name: 'chromium', testIgnore: /mobile\.spec\.ts$/, use: { ...devices['Desktop Chrome'] } },
    {
      name: 'mobile',
      testMatch: /mobile\.spec\.ts$/,
      use: { ...devices['Desktop Chrome'], viewport: { width: 360, height: 780 } },
    },
  ],
});
