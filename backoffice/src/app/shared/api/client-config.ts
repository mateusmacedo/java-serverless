import type { CreateClientConfig } from './generated/client.gen';

// WHY: o dev server (proxy.conf.mjs) leva `/v1` à API da quote-solution e o caminho de cada operação já começa por
// `/v1`; o painel nunca chama a API por outra origem, então não há CORS nem URL de infraestrutura no bundle.
export const createClientConfig: CreateClientConfig = (config) => ({
  ...config,
  baseUrl: '',
});
