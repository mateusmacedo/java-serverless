import type {
  InsurerResult,
  InsurerStatus,
  MissingField,
  Outcome,
  OutcomeReason,
  Phase,
  QuoteVersion,
  UnavailabilityReason,
  Usage,
} from '../../shared/api';

/** Cor do badge do CoreUI; o texto sempre acompanha a cor, que nunca é a única informação. */
export type BadgeColor =
  'primary' | 'secondary' | 'success' | 'info' | 'warning' | 'danger' | 'dark';

export interface Presentation {
  readonly label: string;
  readonly color: BadgeColor;
}

/** Fases em andamento na ordem da linha do tempo; `TERMINAL` é apresentada pelo resultado. */
export const ACTIVE_PHASES: readonly Phase[] = ['RECEIVED', 'VALIDATING', 'ENRICHING', 'QUOTING'];

// INVARIANT: o `satisfies` confere as chaves contra o enum fechado do contrato; um código novo quebra a compilação.
export const PHASES = {
  RECEIVED: {
    label: 'Solicitação recebida',
    description: 'Solicitação recebida — protocolo disponível.',
  },
  VALIDATING: { label: 'Validando dados', description: 'Validando dados.' },
  ENRICHING: {
    label: 'Obtendo informações',
    description: 'Obtendo informações para a cotação.',
  },
  QUOTING: {
    label: 'Consultando seguradoras',
    description: 'Consultando seguradoras — resultados podem aparecer gradualmente.',
  },
  TERMINAL: { label: 'Consultas encerradas', description: 'As consultas desta versão terminaram.' },
} as const satisfies Record<Phase, { label: string; description: string }>;

export const OUTCOMES = {
  COMPLETED: {
    label: 'Consultas concluídas',
    description: 'Consultas concluídas — confira as ofertas e as recusas de cada seguradora.',
    color: 'info',
  },
  PARTIAL: {
    label: 'Ofertas parciais',
    description:
      'Há ofertas disponíveis; algumas consultas não foram concluídas ou precisam de dados.',
    color: 'warning',
  },
  NEEDS_INFORMATION: {
    label: 'Informações adicionais necessárias',
    description:
      'Informações adicionais são necessárias — complemente os dados quando a ação estiver disponível.',
    color: 'warning',
  },
  INELIGIBLE: {
    label: 'Cotação não pode prosseguir',
    description: 'A cotação não pode prosseguir com estes dados.',
    color: 'secondary',
  },
  NO_OFFER: {
    label: 'Nenhuma oferta disponível',
    description: 'Nenhuma seguradora apresentou oferta para este risco.',
    color: 'secondary',
  },
  TECHNICAL_FAILURE: {
    label: 'Falha técnica',
    description:
      'Não foi possível concluir as consultas por um problema técnico. Guarde o protocolo e tente mais tarde ou acione o suporte.',
    color: 'danger',
  },
} as const satisfies Record<Outcome, Presentation & { description: string }>;

export const OUTCOME_REASONS = {
  SALVAGE_VEHICLE: 'O veículo tem registro de salvado, e o risco não é aceito.',
  MISSING_LICENSE_DATE: 'Falta a data da primeira habilitação do condutor principal.',
  ENRICHMENT_NOT_FOUND: 'Não encontramos as informações necessárias do veículo informado.',
  ENRICHMENT_FAILED:
    'O serviço de informações complementares não respondeu; as seguradoras não foram consultadas.',
  NO_APPLICABLE_INSURER: 'Nenhuma seguradora atende a este perfil de risco.',
  ALL_INSURERS_DECLINED: 'Todas as seguradoras consultadas recusaram o risco.',
  INSURER_NEEDS_INFORMATION: 'Uma ou mais seguradoras pediram dados adicionais.',
  INSURER_TECHNICAL_FAILURE: 'As seguradoras ficaram indisponíveis ou não responderam no prazo.',
  WORKFLOW_FAILED: 'O processamento da cotação foi interrompido por uma falha técnica.',
} as const satisfies Record<OutcomeReason, string>;

export const INSURER_STATUSES = {
  PENDING: { label: 'Aguardando', color: 'secondary' },
  PROCESSING: { label: 'Em consulta', color: 'info' },
  QUOTED: { label: 'Oferta disponível', color: 'success' },
  DECLINED: { label: 'Risco não aceito', color: 'dark' },
  NEEDS_INFORMATION: { label: 'Dados adicionais necessários', color: 'warning' },
  UNAVAILABLE: { label: 'Indisponível', color: 'danger' },
  TIMED_OUT: { label: 'Prazo de resposta excedido', color: 'danger' },
} as const satisfies Record<InsurerStatus, Presentation>;

/**
 * Motivos por seguradora que o painel sabe explicar. O contrato deixa `reasonCode` aberto; um código desconhecido
 * não aparece, para o código cru nunca chegar à tela.
 */
const INSURER_REASONS: Readonly<Record<string, string>> = {
  RISK_NOT_ACCEPTED: 'A seguradora não aceitou o risco.',
  INSURER_NEEDS_INFORMATION: 'A seguradora pediu dados adicionais.',
  STEP_TIMEOUT: 'A seguradora não respondeu dentro do prazo.',
  PROVIDER_UNAVAILABLE: 'O serviço da seguradora estava indisponível.',
};

export const UNAVAILABILITY_REASONS = {
  VERSION_SUPERSEDED: 'Versão substituída por uma mais nova',
  SELECTION_ALREADY_EXISTS: 'Outra oferta já foi selecionada nesta cotação',
  OFFER_EXPIRED: 'Oferta expirada',
} as const satisfies Record<UnavailabilityReason, string>;

export const USAGES = {
  PERSONAL: 'Particular',
  COMMERCIAL: 'Comercial',
} as const satisfies Record<Usage, string>;

export const USAGE_OPTIONS: readonly Usage[] = ['PERSONAL', 'COMMERCIAL'];

/** Situação resumida da cotação para a lista e o cabeçalho: o resultado, quando terminou, ou a fase. */
export function quoteStatus(phase: Phase, outcome: Outcome | null | undefined): Presentation {
  if (phase === 'TERMINAL' && outcome) {
    return { label: OUTCOMES[outcome].label, color: OUTCOMES[outcome].color };
  }
  return { label: PHASES[phase].label, color: phase === 'TERMINAL' ? 'secondary' : 'primary' };
}

export function insurerReason(result: InsurerResult): string | null {
  return result.reasonCode ? (INSURER_REASONS[result.reasonCode] ?? null) : null;
}

/** Indica se a consulta da seguradora ainda pode mudar. */
export function isInsurerActive(status: InsurerStatus): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

/** Pendência para complementar, com a seguradora que a pediu; `null` nas pendências da própria versão. */
export interface Pending {
  readonly field: MissingField;
  readonly insurerName: string | null;
}

/**
 * Pendências da versão nos dois lugares do contrato: as da própria versão (`missingFields`) e as pedidas por cada
 * seguradora (`insurerResults[].missingFields`). A mesma pendência pedida duas vezes aparece uma vez só.
 */
export function pendingFields(version: QuoteVersion): Pending[] {
  const seen = new Set<string>();
  const all: Pending[] = [
    ...version.missingFields.map((field) => ({ field, insurerName: null })),
    ...version.insurerResults.flatMap((result) =>
      result.missingFields.map((field) => ({ field, insurerName: result.insurerName })),
    ),
  ];
  return all.filter(({ field }) => {
    const key = `${field.path}|${field.reasonCode}`;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

/**
 * Campo do formulário que resolve a pendência: o caminho vem do snapshot (`risk.principalDriver.licenseDate`), e o
 * formulário só escolhe referências do catálogo, então a pendência de um dado cadastral leva à troca da referência.
 */
export function pendingControl(path: string): string | null {
  const rules: readonly (readonly [string, string])[] = [
    ['risk.principalDriver', 'risk.principalDriverRef'],
    ['risk.vehicle', 'risk.vehicleRef'],
    ['risk.usage', 'risk.usage'],
    ['applicant', 'applicantRef'],
    ['coverage', 'coverageProfile'],
    ['desiredStartDate', 'desiredStartDate'],
  ];
  return rules.find(([prefix]) => path === prefix || path.startsWith(prefix))?.[1] ?? null;
}

/** Início do id opaco para a coluna estreita ("5b0b6c1e…"); o completo vai no título. */
export function shortId(id: string | null | undefined): string {
  return id ? `${id.slice(0, 8)}…` : '';
}
