import { compareDecimal, formatMoney, isDecimal, sameDecimal } from './money';

describe('formatMoney', () => {
  it('formata o decimal do contrato em reais, sem arredondar pelo number', () => {
    expect(formatMoney('2450.90', 'BRL')).toBe('R$ 2.450,90');
    expect(formatMoney('0', 'BRL')).toBe('R$ 0,00');
    expect(formatMoney('12345678901234567.89', 'BRL')).toBe('R$ 12.345.678.901.234.567,89');
  });

  it('não transforma ausência em zero', () => {
    expect(formatMoney(undefined, 'BRL')).toBe('');
    expect(formatMoney(null, 'BRL')).toBe('');
    expect(formatMoney('não é número', 'BRL')).toBe('');
    expect(formatMoney('10.00', null)).toBe('');
  });
});

describe('compareDecimal', () => {
  it('compara pelo valor decimal, com zeros à esquerda e à direita', () => {
    expect(compareDecimal('2450.90', '2389.40')).toBeGreaterThan(0);
    expect(compareDecimal('99.9', '100')).toBeLessThan(0);
    expect(compareDecimal('010.50', '10.5')).toBe(0);
    expect(sameDecimal('10', '10.000')).toBe(true);
  });

  it('ordena negativos antes dos positivos', () => {
    expect(compareDecimal('-1', '0')).toBeLessThan(0);
    expect(compareDecimal('-2', '-1')).toBeLessThan(0);
    expect(compareDecimal('-0', '0')).toBe(0);
  });

  it('confere o formato do contrato', () => {
    expect(isDecimal('1.5')).toBe(true);
    expect(isDecimal('1,5')).toBe(false);
    expect(isDecimal('')).toBe(false);
  });
});
