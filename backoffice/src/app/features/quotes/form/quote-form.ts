import { FormControl, FormGroup, Validators } from '@angular/forms';

import type { CreateQuoteRequest, Snapshot, Usage } from '../../../shared/api';

export type QuoteForm = FormGroup<{
  applicantRef: FormControl<string | null>;
  risk: FormGroup<{
    vehicleRef: FormControl<string | null>;
    usage: FormControl<Usage | null>;
    principalDriverRef: FormControl<string | null>;
  }>;
  coverageProfile: FormControl<string | null>;
  desiredStartDate: FormControl<string>;
}>;

/** Rótulos das escolhas, para a revisão e o resumo, pelo caminho do campo no contrato. */
export type QuoteFormLabels = Readonly<Partial<Record<QuoteFieldPath, string | null>>>;

export type QuoteFieldPath =
  | 'applicantRef'
  | 'risk.vehicleRef'
  | 'risk.usage'
  | 'risk.principalDriverRef'
  | 'coverageProfile'
  | 'desiredStartDate';

/** Ordem dos campos na tela, para o foco no primeiro inválido e a lista de erros. */
export const QUOTE_FIELD_LABELS: Readonly<Record<QuoteFieldPath | 'product', string>> = {
  product: 'Produto',
  applicantRef: 'Proponente',
  'risk.vehicleRef': 'Veículo',
  'risk.principalDriverRef': 'Condutor principal',
  'risk.usage': 'Uso do veículo',
  coverageProfile: 'Perfil de cobertura',
  desiredStartDate: 'Início de vigência desejado',
};

const CIVIL_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Formulário do pedido: todas as referências vêm do catálogo e são obrigatórias. O cliente confere só a presença e o
 * formato da data; a elegibilidade da data e as regras de subscrição são do servidor.
 */
export function createQuoteForm(initial?: Partial<CreateQuoteRequest>): QuoteForm {
  return new FormGroup({
    applicantRef: new FormControl<string | null>(
      initial?.applicantRef ?? null,
      Validators.required,
    ),
    risk: new FormGroup({
      vehicleRef: new FormControl<string | null>(
        initial?.risk?.vehicleRef ?? null,
        Validators.required,
      ),
      usage: new FormControl<Usage | null>(initial?.risk?.usage ?? null, Validators.required),
      principalDriverRef: new FormControl<string | null>(
        initial?.risk?.principalDriverRef ?? null,
        Validators.required,
      ),
    }),
    coverageProfile: new FormControl<string | null>(
      initial?.coverageProfile ?? null,
      Validators.required,
    ),
    desiredStartDate: new FormControl(initial?.desiredStartDate ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(CIVIL_DATE)],
    }),
  });
}

/** Corpo de `POST /v1/quotes` a partir do formulário válido. */
export function toCreateQuoteRequest(form: QuoteForm): CreateQuoteRequest {
  const value = form.getRawValue();
  return {
    product: 'AUTO',
    applicantRef: value.applicantRef ?? '',
    risk: {
      vehicleRef: value.risk.vehicleRef ?? '',
      usage: value.risk.usage ?? 'PERSONAL',
      principalDriverRef: value.risk.principalDriverRef ?? '',
    },
    coverageProfile: value.coverageProfile ?? '',
    desiredStartDate: value.desiredStartDate,
  };
}

/** Pedido da versão a partir do snapshot, para a complementação começar dos dados atuais. */
export function requestFromSnapshot(snapshot: Snapshot): CreateQuoteRequest {
  return {
    product: 'AUTO',
    applicantRef: snapshot.applicantRef,
    risk: {
      vehicleRef: snapshot.risk.vehicleRef,
      usage: snapshot.risk.usage,
      principalDriverRef: snapshot.risk.principalDriverRef,
    },
    coverageProfile: snapshot.coverageProfile,
    desiredStartDate: snapshot.desiredStartDate,
  };
}

/** Rótulos do snapshot, para a complementação mostrar as escolhas atuais antes de o catálogo carregar. */
export function labelsFromSnapshot(snapshot: Snapshot): QuoteFormLabels {
  return {
    applicantRef: snapshot.applicant.label,
    'risk.vehicleRef': snapshot.risk.vehicle.label,
    'risk.principalDriverRef': snapshot.risk.principalDriver.label,
    coverageProfile: snapshot.coverage.label,
  };
}
