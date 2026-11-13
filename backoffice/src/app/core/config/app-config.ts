import { InjectionToken } from '@angular/core';

/**
 * Configuração de execução do painel, servida em `/config.json` e gerada pelo `pnpm configure` a partir da stack
 * implantada. Só valores públicos: o app client do Cognito não tem segredo, e a API e o Cognito ficam atrás do mesmo
 * endereço do painel (`/v1` e `/cognito`), então nenhuma URL de infraestrutura entra no bundle.
 */
export interface AppConfig {
  /** Stage da stack (`local` no Floci, `dev` na AWS), mostrado no rodapé. */
  readonly stage: string;
  readonly cognito: {
    /** App client `quote-solution-<stage>-web`, sem segredo. */
    readonly clientId: string;
    /** Caminho que leva às APIs do Cognito, como `/cognito`. */
    readonly endpoint: string;
  };
  /** Intervalo do polling quando a versão não informa `retryAfterSeconds` (3 s no contrato). */
  readonly pollIntervalSeconds: number;
  /** Prazo de cada chamada à API; esgotado, a mutação fica com resultado desconhecido. */
  readonly requestTimeoutSeconds: number;
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

export const CONFIG_URL = '/config.json';

/** Valida o `/config.json`; lança com a lista do que falta, para o erro de inicialização. */
export function parseAppConfig(value: unknown): AppConfig {
  const record = isRecord(value) ? value : {};
  const cognito = isRecord(record['cognito']) ? record['cognito'] : {};
  const problems = [
    typeof record['stage'] === 'string' && record['stage'] ? '' : 'stage',
    typeof cognito['clientId'] === 'string' && cognito['clientId'] ? '' : 'cognito.clientId',
    typeof cognito['endpoint'] === 'string' && cognito['endpoint'] ? '' : 'cognito.endpoint',
  ].filter(Boolean);
  if (problems.length) {
    throw new Error(`config.json sem ${problems.join(', ')}`);
  }
  return {
    stage: record['stage'] as string,
    cognito: { clientId: cognito['clientId'] as string, endpoint: cognito['endpoint'] as string },
    pollIntervalSeconds: positive(record['pollIntervalSeconds'], 3),
    requestTimeoutSeconds: positive(record['requestTimeoutSeconds'], 30),
  };
}

/** Lê o `/config.json` antes de iniciar o app, sem cache, porque ele muda a cada implantação da stack. */
export async function loadAppConfig(fetchFn: typeof fetch = fetch): Promise<AppConfig> {
  const response = await fetchFn(CONFIG_URL, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`GET ${CONFIG_URL} respondeu ${response.status}`);
  }
  return parseAppConfig(await response.json());
}

function positive(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
