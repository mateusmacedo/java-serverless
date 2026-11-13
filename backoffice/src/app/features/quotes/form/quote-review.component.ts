import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { CreateQuoteRequest } from '../../../shared/api';
import { formatCivilDate } from '../../../shared/util/dates';
import { USAGES } from '../quote-presentation';
import { QUOTE_FIELD_LABELS, type QuoteFormLabels } from './quote-form';

/** Revisão do pedido antes do envio: o que será cotado, pelos rótulos das escolhas. */
@Component({
  selector: 'app-quote-review',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dl class="row mb-0">
      @for (row of rows(); track row.label) {
        <dt class="col-sm-4">{{ row.label }}</dt>
        <dd class="col-sm-8">{{ row.value }}</dd>
      }
    </dl>
  `,
})
export class QuoteReviewComponent {
  readonly request = input.required<CreateQuoteRequest>();
  readonly labels = input<QuoteFormLabels>({});

  readonly rows = computed(() => {
    const request = this.request();
    const labels = this.labels();
    return [
      { label: QUOTE_FIELD_LABELS.product, value: 'Seguro auto' },
      {
        label: QUOTE_FIELD_LABELS.applicantRef,
        value: labels.applicantRef ?? request.applicantRef,
      },
      {
        label: QUOTE_FIELD_LABELS['risk.vehicleRef'],
        value: labels['risk.vehicleRef'] ?? request.risk.vehicleRef,
      },
      {
        label: QUOTE_FIELD_LABELS['risk.principalDriverRef'],
        value: labels['risk.principalDriverRef'] ?? request.risk.principalDriverRef,
      },
      { label: QUOTE_FIELD_LABELS['risk.usage'], value: USAGES[request.risk.usage] },
      {
        label: QUOTE_FIELD_LABELS.coverageProfile,
        value: labels.coverageProfile ?? request.coverageProfile,
      },
      {
        label: QUOTE_FIELD_LABELS.desiredStartDate,
        value: formatCivilDate(request.desiredStartDate),
      },
    ];
  });
}
