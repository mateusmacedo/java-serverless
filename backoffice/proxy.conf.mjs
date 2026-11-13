// Proxy do dev server: `/v1` vai à API da quote-solution e `/cognito` às APIs do Cognito, com os endereços que o
// `pnpm configure` resolveu. O navegador só fala com http://localhost:4200, sem CORS.
import { existsSync, readFileSync } from 'node:fs';

const file = new URL('./runtime/environment.json', import.meta.url);

function proxies() {
  if (!existsSync(file)) {
    console.warn(
      '\n[backoffice] runtime/environment.json não existe: rode `pnpm configure` com a quote-solution implantada.' +
        '\n[backoffice] Sem ele, o dev server sobe sem proxy, e as chamadas à API e ao login falham.\n',
    );
    return {};
  }
  const environment = JSON.parse(readFileSync(file, 'utf8'));
  return {
    '/v1': {
      target: environment.api.origin,
      secure: true,
      changeOrigin: true,
      pathRewrite: { '^/v1': `${environment.api.basePath}/v1` },
    },
    '/cognito': {
      target: environment.cognito.origin,
      secure: true,
      changeOrigin: true,
      pathRewrite: { '^/cognito': '/' },
    },
  };
}

export default proxies();
