import type { Offer, OffersPage } from '../../shared/api';
import { body } from '../../../testing/fixtures';
import { buildComparison } from './offer-comparison';
import {
  installmentsText,
  looksExpired,
  offerAvailability,
  sortByPremium,
} from './offer-presentation';

function offer(overrides: Partial<Offer>): Offer {
  return {
    id: 'of_a',
    insurerId: 'alpha',
    insurerName: 'Alpha Seguros',
    premiumTotal: '2450.90',
    currency: 'BRL',
    installments: [{ count: 1, amount: '2450.90' }],
    coverages: [
      { code: 'COLLISION', label: 'Colisão', insuredAmount: '80000.00' },
      { code: 'THEFT', label: 'Roubo e furto', insuredAmount: '80000.00' },
    ],
    deductibles: [{ coverageCode: 'COLLISION', amount: '3500.00' }],
    validUntil: '2026-11-19T10:00:00.000Z',
    selectable: true,
    unavailabilityReason: null,
    ...overrides,
  };
}

describe('buildComparison (FT14)', () => {
  it('marca limite e franquia diferentes e mostra a ausência como estado próprio', () => {
    const alpha = offer({});
    const beta = offer({
      id: 'of_b',
      insurerName: 'Beta Seguros',
      coverages: [{ code: 'COLLISION', label: 'Colisão', insuredAmount: '75000.00' }],
      deductibles: [],
    });

    const rows = buildComparison([alpha, beta]);
    const collision = rows.find((row) => row.key === 'coverage:COLLISION')!;
    const theft = rows.find((row) => row.key === 'coverage:THEFT')!;
    const deductible = rows.find((row) => row.key === 'deductible:COLLISION')!;

    expect(collision.divergent).toBe(true);
    expect(theft.cells.map((cell) => cell.text)).toEqual(['R$ 80.000,00', 'Não incluída']);
    expect(theft.divergent).toBe(true);
    expect(deductible.cells[1]).toEqual({ kind: 'not-informed', text: 'Não informado' });
    expect(deductible.label).toBe('Franquia de Colisão');
  });

  it('não confunde zero com ausência nem marca valores iguais escritos de outro jeito', () => {
    const zero = offer({
      coverages: [{ code: 'GLASS', label: 'Vidros', insuredAmount: '0' }],
      deductibles: [],
    });
    const missing = offer({
      id: 'of_b',
      coverages: [{ code: 'GLASS', label: 'Vidros' }],
      deductibles: [],
    });
    const same = offer({
      id: 'of_c',
      coverages: [{ code: 'GLASS', label: 'Vidros', insuredAmount: '0.00' }],
      deductibles: [],
    });

    const glass = buildComparison([zero, missing]).find((row) => row.key === 'coverage:GLASS')!;
    const equal = buildComparison([zero, same]).find((row) => row.key === 'coverage:GLASS')!;

    expect(glass.cells.map((cell) => cell.kind)).toEqual(['value', 'not-informed']);
    expect(glass.cells[0].text).toBe('R$ 0,00');
    expect(equal.divergent).toBe(false);
  });

  it('compara as ofertas reais da fixture de oferta parcial', () => {
    const page = body<OffersPage>('quotePartialOffer', 'ofertas parciais');

    expect(() => buildComparison(page.items)).not.toThrow();
  });
});

describe('apresentação da oferta', () => {
  it('ordena pelo prêmio sem arredondar', () => {
    const sorted = sortByPremium([
      offer({ id: 'caro', premiumTotal: '2450.90' }),
      offer({ id: 'barato', premiumTotal: '2450.09' }),
    ]);

    expect(sorted.map((item) => item.id)).toEqual(['barato', 'caro']);
  });

  it('descreve as parcelas e a falta delas', () => {
    expect(
      installmentsText(
        [
          { count: 10, amount: '245.09' },
          { count: 1, amount: '2450.90' },
        ],
        'BRL',
      ),
    ).toBe('1x de R$ 2.450,90 · 10x de R$ 245,09');
    expect(installmentsText([], 'BRL')).toBe('Não informado');
  });

  it('estima a validade pelo relógio do servidor', () => {
    const item = offer({ validUntil: '2026-11-12T10:00:00.000Z' });
    const localNow = Date.parse('2026-11-12T09:59:00.000Z');

    expect(looksExpired(item, 0, localNow)).toBe(false);
    expect(looksExpired(item, 120_000, localNow)).toBe(true);
  });

  it('mostra a selecionada e o motivo de quem não pode ser selecionada', () => {
    const selected = offer({ selectable: false, unavailabilityReason: 'SELECTION_ALREADY_EXISTS' });
    const other = offer({
      id: 'of_b',
      selectable: false,
      unavailabilityReason: 'SELECTION_ALREADY_EXISTS',
    });
    const selection = {
      selectionId: 's',
      offerId: 'of_a',
      selectedAt: '',
      status: 'INTENT_RECORDED' as const,
    };

    expect(offerAvailability(selected, selection).label).toBe('Selecionada');
    expect(offerAvailability(other, selection).label).toBe(
      'Outra oferta já foi selecionada nesta cotação',
    );
  });
});
