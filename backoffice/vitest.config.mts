import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // WHY: o padrão é um worker por núcleo menos um, e cada um carrega Angular e jsdom; ao lado do Floci e do
    // editor, isso esgota a memória do host. VITEST_MAX_WORKERS sobrepõe este teto.
    maxWorkers: Number(process.env['VITEST_MAX_WORKERS'] ?? 2),
  },
});
