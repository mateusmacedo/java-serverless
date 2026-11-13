import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  AlertComponent,
  BadgeComponent,
  ButtonDirective,
  ListGroupDirective,
  ListGroupItemDirective,
} from '@coreui/angular';

import type { InsurerResult, Phase, QuoteVersion } from '../../../shared/api';
import { CivilDatePipe, DateTimePipe, RelativeTimePipe } from '../../../shared/ui/pipes';
import {
  ACTIVE_PHASES,
  INSURER_STATUSES,
  insurerReason,
  OUTCOMES,
  PHASES,
  type Pending,
  USAGES,
} from '../quote-presentation';

/**
 * Aviso da versão substituída: a versão continua consultável, mas a seleção e a complementação ficam na versão atual.
 */
@Component({
  selector: 'app-version-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent, DateTimePipe, RouterLink],
  template: `
    @if (superseded()) {
      <c-alert color="info" role="status">
        <p class="mb-1">
          Esta é a versão {{ version().version }}, substituída
          @if (version().supersededAt) {
            em {{ version().supersededAt | dateTime }}
          }
          pela versão {{ version().currentVersion }}. O histórico continua disponível para consulta; a seleção e a
          complementação ficam na versão atual.
        </p>
        <a [routerLink]="['/quotes', version().quoteId, 'versions', version().currentVersion]">Abrir a versão atual</a>
      </c-alert>
    }
  `,
})
export class VersionBannerComponent {
  readonly version = input.required<QuoteVersion>();

  readonly superseded = computed(
    () =>
      this.version().supersededAt !== null ||
      this.version().version < this.version().currentVersion,
  );
}

/**
 * Linha do tempo das fases, sem porcentagem: as fases em andamento e, ao fim, o resultado. O texto diz o estado de
 * cada passo, então a cor nunca é a única informação.
 */
@Component({
  selector: 'app-phase-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol aria-label="Andamento da cotação" class="phase-timeline mb-3">
      @for (step of steps(); track step.key) {
        <li [attr.aria-current]="step.state === 'current' ? 'step' : null" [class.current]="step.state === 'current'" [class.done]="step.state === 'done'">
          <span class="d-block small">{{ step.label }}</span>
          <span class="visually-hidden">: {{ stateText[step.state] }}</span>
        </li>
      }
    </ol>
  `,
})
export class PhaseTimelineComponent {
  readonly version = input.required<QuoteVersion>();

  readonly stateText = { done: 'concluída', current: 'em andamento', todo: 'a seguir' } as const;

  readonly steps = computed(() => {
    const version = this.version();
    const index = ACTIVE_PHASES.indexOf(version.phase);
    const terminal = version.phase === 'TERMINAL';
    const phases = ACTIVE_PHASES.map((phase: Phase, position) => {
      let state: 'done' | 'current' | 'todo';

      if (terminal || position < index) {
        state = 'done';
      } else if (position === index) {
        state = 'current';
      } else {
        state = 'todo';
      }

      return {
        key: phase,
        label: PHASES[phase].label,
        state,
      };
    });
    const result = {
      key: 'TERMINAL',
      label: terminal && version.outcome ? OUTCOMES[version.outcome].label : 'Resultado',
      state: terminal ? 'current' : 'todo',
    };
    return [...phases, result] as readonly {
      key: string;
      label: string;
      state: 'done' | 'current' | 'todo';
    }[];
  });
}

/** Resultado de cada seguradora: estado, motivo seguro e data da última mudança. */
@Component({
  selector: 'app-insurer-result-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BadgeComponent, ListGroupDirective, ListGroupItemDirective, RelativeTimePipe],
  template: `
    <ul aria-label="Resultados por seguradora" cListGroup class="mb-3">
      @for (result of results(); track result.insurerId) {
        <li cListGroupItem class="d-flex flex-wrap gap-2 align-items-center">
          <span class="fw-semibold me-auto">{{ result.insurerName }}</span>
          <c-badge [color]="status(result).color">{{ status(result).label }}</c-badge>
          @if (reason(result); as text) {
            <span class="w-100 small text-body-secondary">{{ text }}</span>
          }
          @if (result.missingFields.length) {
            <span class="w-100 small">
              Pediu: {{ missingLabels(result) }}
            </span>
          }
          <span class="w-100 small text-body-secondary">Atualizado {{ result.updatedAt | relativeTime }}</span>
        </li>
      } @empty {
        <li cListGroupItem class="text-body-secondary">As seguradoras ainda não foram escolhidas para esta cotação.</li>
      }
    </ul>
  `,
})
export class InsurerResultListComponent {
  readonly results = input.required<readonly InsurerResult[]>();

  status(result: InsurerResult) {
    return INSURER_STATUSES[result.status];
  }

  reason(result: InsurerResult): string | null {
    return insurerReason(result);
  }

  missingLabels(result: InsurerResult): string {
    return result.missingFields.map((field) => field.label).join(', ');
  }
}

/**
 * Pendências para complementar (PRD FE08): as da versão e as de cada seguradora. A ação só aparece quando o servidor
 * oferece `CREATE_VERSION`; depois de uma seleção, a pendência fica só como informação.
 */
@Component({
  selector: 'app-missing-information-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AlertComponent, ButtonDirective, RouterLink],
  template: `
    @if (pending().length) {
      <c-alert color="warning">
        <p class="fw-semibold mb-1">Informações adicionais necessárias</p>
        <ul class="mb-2 ps-3">
          @for (item of pending(); track item.field.path + item.field.reasonCode) {
            <li>
              {{ item.field.label }}
              @if (item.insurerName) {
                <span class="text-body-secondary">(pedido pela {{ item.insurerName }})</span>
              }
            </li>
          }
        </ul>
        @if (canComplement()) {
          <a [routerLink]="['edit']" cButton color="warning" size="sm">Complementar dados</a>
          <p class="small mb-0 mt-2">A complementação cria uma nova versão; esta continua no histórico.</p>
        } @else if (selected()) {
          <p class="small mb-0">A cotação já tem uma oferta selecionada; a pendência fica só como informação.</p>
        } @else {
          <p class="small mb-0">A complementação não está disponível para você nesta versão.</p>
        }
      </c-alert>
    }
  `,
})
export class MissingInformationPanelComponent {
  readonly pending = input.required<readonly Pending[]>();
  readonly canComplement = input(false);
  readonly selected = input(false);
}

/** Dados que a versão cotou, do snapshot imutável, com o protocolo. */
@Component({
  selector: 'app-quote-summary',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CivilDatePipe],
  template: `
    <dl class="row mb-0 small">
      <dt class="col-sm-5 col-lg-4">Protocolo</dt>
      <dd class="col-sm-7 col-lg-8 text-break"><code>{{ version().quoteId }}</code></dd>
      <dt class="col-sm-5 col-lg-4">Versão</dt>
      <dd class="col-sm-7 col-lg-8">{{ version().version }}</dd>
      <dt class="col-sm-5 col-lg-4">Produto</dt>
      <dd class="col-sm-7 col-lg-8">Seguro auto</dd>
      <dt class="col-sm-5 col-lg-4">Proponente</dt>
      <dd class="col-sm-7 col-lg-8">{{ snapshot().applicant.label }}</dd>
      <dt class="col-sm-5 col-lg-4">Veículo</dt>
      <dd class="col-sm-7 col-lg-8">{{ snapshot().risk.vehicle.label }} ({{ snapshot().risk.vehicle.year }})</dd>
      <dt class="col-sm-5 col-lg-4">Condutor principal</dt>
      <dd class="col-sm-7 col-lg-8">
        {{ snapshot().risk.principalDriver.label }}
        <span class="d-block text-body-secondary">
          Primeira habilitação:
          {{ snapshot().risk.principalDriver.licenseDate ? (snapshot().risk.principalDriver.licenseDate | civilDate) : 'Não informada' }}
        </span>
      </dd>
      <dt class="col-sm-5 col-lg-4">Uso</dt>
      <dd class="col-sm-7 col-lg-8">{{ usage() }}</dd>
      <dt class="col-sm-5 col-lg-4">Perfil de cobertura</dt>
      <dd class="col-sm-7 col-lg-8">{{ snapshot().coverage.label }}</dd>
      <dt class="col-sm-5 col-lg-4">Início de vigência</dt>
      <dd class="col-sm-7 col-lg-8">{{ snapshot().desiredStartDate | civilDate }}</dd>
    </dl>
  `,
})
export class QuoteSummaryComponent {
  readonly version = input.required<QuoteVersion>();

  readonly snapshot = computed(() => this.version().snapshot);
  readonly usage = computed(() => USAGES[this.snapshot().risk.usage]);
}
