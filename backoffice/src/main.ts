import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { createAppConfig } from './app/app.config';
import { loadAppConfig } from './app/core/config/app-config';

loadAppConfig()
  .then((config) => bootstrapApplication(AppComponent, createAppConfig(config)))
  .catch((error: unknown) => {
    console.error('Falha ao iniciar o painel', error);
    const root = document.querySelector('app-root');
    if (root) {
      root.innerHTML = `<div class="container py-5" role="alert">
        <h1 class="h4">Não foi possível abrir o painel</h1>
        <p>A configuração do ambiente não foi carregada. Recarregue a página; se o erro continuar, avise o suporte.</p>
      </div>`;
    }
  });
