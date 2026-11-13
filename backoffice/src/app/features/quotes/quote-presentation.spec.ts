import type { QuoteVersion } from '../../shared/api';
import { body } from '../../../testing/fixtures';
import {
  insurerReason,
  OUTCOMES,
  pendingControl,
  pendingFields,
  quoteStatus,
} from './quote-presentation';

describe('apresentação da cotação', () => {
  it('distingue recusa comercial de falha técnica (FT05)', () => {
    const declined = body<QuoteVersion>('quoteDeclined', 'todas recusaram');
    const unavailable = body<QuoteVersion>('quoteUnavailable', 'seguradoras indisponíveis');

    const commercial = quoteStatus(declined.phase, declined.outcome);
    const technical = quoteStatus(unavailable.phase, unavailable.outcome);

    expect(commercial.label).toBe('Nenhuma oferta disponível');
    expect(technical.label).toBe('Falha técnica');
    expect(commercial.color).not.toBe(technical.color);
    expect(OUTCOMES.NO_OFFER.description).not.toMatch(/técnic/);
  });

  it('não usa "concluída" sozinho para o resultado com ofertas', () => {
    expect(quoteStatus('TERMINAL', 'COMPLETED').label).toBe('Consultas concluídas');
  });

  it('mostra a fase enquanto não há resultado', () => {
    expect(quoteStatus('QUOTING', null).label).toBe('Consultando seguradoras');
  });

  it('explica o motivo da seguradora e esconde o código desconhecido', () => {
    const declined = body<QuoteVersion>('quoteDeclined', 'uma oferta e uma recusa');
    const beta = declined.insurerResults.find((result) => result.status === 'DECLINED');

    expect(beta && insurerReason(beta)).toBe('A seguradora não aceitou o risco.');
    expect(insurerReason({ ...beta!, reasonCode: 'CODIGO_NOVO' })).toBeNull();
  });
});

describe('pendências', () => {
  it('junta as pendências da versão e as das seguradoras (PARTIAL com pendência)', () => {
    const version = body<QuoteVersion>(
      'quotePartialNeedsInformation',
      'PARTIAL com a pendência da beta',
    );

    const pending = pendingFields(version);

    expect(version.missingFields).toEqual([]);
    expect(pending.length).toBeGreaterThan(0);
    expect(pending.every((item) => item.insurerName === 'Beta Seguros')).toBe(true);
  });

  it('leva a pendência de cadastro à troca da referência no formulário', () => {
    expect(pendingControl('risk.principalDriver.licenseDate')).toBe('risk.principalDriverRef');
    expect(pendingControl('risk.vehicleRef')).toBe('risk.vehicleRef');
    expect(pendingControl('applicant.document')).toBe('applicantRef');
    expect(pendingControl('desconhecido')).toBeNull();
  });
});
