import { provideHttpClient, withInterceptors } from '@angular/common/http';
import type { ApplicationConfig } from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withRouterConfig,
  withViewTransitions,
} from '@angular/router';
import { IconSetService } from '@coreui/icons-angular';

import { routes } from './app.routes';
import { provideSession } from './core/auth/session-store';
import { APP_CONFIG, type AppConfig } from './core/config/app-config';
import { authInterceptor } from './core/http/auth.interceptor';
import { correlationIdInterceptor } from './core/http/correlation-id.interceptor';
import { timeoutInterceptor } from './core/http/timeout.interceptor';
import { provideQuoteApi } from './shared/api';

/** Providers do app com a configuração de execução já lida do `/config.json`. */
export function createAppConfig(config: AppConfig): ApplicationConfig {
  return {
    providers: [
      { provide: APP_CONFIG, useValue: config },
      provideRouter(
        routes,
        // NOTE: `always` leva `quoteId` e `version` do grupo da versão às rotas filhas (comparação e complementação).
        withRouterConfig({ onSameUrlNavigation: 'reload', paramsInheritanceStrategy: 'always' }),
        withInMemoryScrolling({ scrollPositionRestoration: 'top', anchorScrolling: 'enabled' }),
        withComponentInputBinding(),
        withViewTransitions(),
      ),
      // NOTE: o primeiro da lista é o mais externo; o prazo vale para cada tentativa, inclusive a repetida no 401.
      provideHttpClient(
        withInterceptors([correlationIdInterceptor, authInterceptor, timeoutInterceptor]),
      ),
      provideQuoteApi(),
      provideSession(),
      IconSetService,
    ],
  };
}
