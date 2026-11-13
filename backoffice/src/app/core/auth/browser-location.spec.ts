import { safeReturnUrl } from './browser-location';

describe('safeReturnUrl', () => {
  it('aceita só caminhos deste painel', () => {
    expect(safeReturnUrl('/quotes/abc/versions/1')).toBe('/quotes/abc/versions/1');
    expect(safeReturnUrl('/operations?status=OPEN')).toBe('/operations?status=OPEN');
  });

  it('recusa outro host, protocolo relativo e a própria tela de login', () => {
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('//evil.example/quotes')).toBe('/');
    expect(safeReturnUrl('/\\evil.example')).toBe('/');
    expect(safeReturnUrl('/login?expired=1')).toBe('/');
    expect(safeReturnUrl(null)).toBe('/');
  });
});
