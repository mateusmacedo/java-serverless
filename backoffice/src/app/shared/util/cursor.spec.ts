import { back, canGoBack, followCursor, forward, startTrail, toPageSize } from './cursor';

describe('pilha de cursores', () => {
  it('avança e volta pelas páginas visitadas', () => {
    const second = forward(startTrail(), 'c2');
    const third = forward(second, 'c3');

    expect(back(third)).toEqual(second);
    expect(back(back(third)).current).toBeUndefined();
    expect(canGoBack(startTrail())).toBe(false);
  });

  it('com cursor na URL depois de recarregar, Anterior leva à primeira página', () => {
    expect(back(startTrail('c3')).current).toBeUndefined();
  });

  it('acompanha o botão voltar do navegador e recomeça sem cursor', () => {
    const third = forward(forward(startTrail(), 'c2'), 'c3');

    expect(followCursor(third, 'c2')).toEqual({ current: 'c2', previous: [undefined] });
    expect(followCursor(third, null)).toEqual(startTrail());
  });

  it('aceita só os tamanhos de página oferecidos', () => {
    expect(toPageSize('50')).toBe(50);
    expect(toPageSize('7')).toBe(20);
  });
});
