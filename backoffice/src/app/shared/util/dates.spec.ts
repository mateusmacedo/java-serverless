import {
  endOfCivilDay,
  formatCivilDate,
  formatDateTime,
  formatRelative,
  startOfCivilDay,
  todayCivilDate,
} from './dates';

describe('datas no fuso de São Paulo', () => {
  it('formata o instante UTC no horário de São Paulo', () => {
    expect(formatDateTime('2026-11-12T10:00:00Z')).toBe('12/11/2026 07:00');
    expect(formatDateTime('não é data')).toBe('');
  });

  it('mostra a data civil do contrato no formato brasileiro', () => {
    expect(formatCivilDate('2026-12-01')).toBe('01/12/2026');
    expect(formatCivilDate('01/12/2026')).toBe('');
  });

  it('converte o dia civil nos instantes inclusivos do filtro', () => {
    expect(startOfCivilDay('2026-11-12')).toBe('2026-11-12T03:00:00.000Z');
    expect(endOfCivilDay('2026-11-12')).toBe('2026-11-13T02:59:59.999Z');
    expect(startOfCivilDay('')).toBeUndefined();
  });

  it('calcula o dia de hoje em São Paulo, não no fuso da máquina', () => {
    expect(todayCivilDate('2026-11-13T02:30:00Z')).toBe('2026-11-12');
  });

  it('descreve a distância até agora', () => {
    const now = '2026-11-12T10:00:00Z';
    expect(formatRelative('2026-11-12T09:59:30Z', now)).toBe('agora');
    expect(formatRelative('2026-11-12T09:55:00Z', now)).toBe('há 5 minutos');
    expect(formatRelative('2026-11-11T10:00:00Z', now)).toBe('ontem');
  });
});
