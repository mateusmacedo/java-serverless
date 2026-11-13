/** Prefixo das chamadas à API de produto; só elas levam token, correlação e prazo. */
export const API_PREFIX = '/v1/';

export function isApiRequest(url: string): boolean {
  return url.startsWith(API_PREFIX);
}
