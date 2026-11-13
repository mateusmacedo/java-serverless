import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { BadgeComponent } from '@coreui/angular';

import type { Outcome, Phase } from '../../../shared/api';
import { quoteStatus } from '../quote-presentation';

/** Situação da cotação em texto e cor: a fase enquanto há consultas, o resultado depois. */
@Component({
  selector: 'app-quote-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent],
  template: `<c-badge [color]="status().color" class="text-wrap text-start">{{ status().label }}</c-badge>`,
})
export class QuoteStatusBadgeComponent {
  readonly phase = input.required<Phase>();
  readonly outcome = input<Outcome | null>(null);

  readonly status = computed(() => quoteStatus(this.phase(), this.outcome()));
}
