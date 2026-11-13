import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  input,
  type OnInit,
  output,
  signal,
  untracked,
} from '@angular/core';
import { type AbstractControl, ReactiveFormsModule } from '@angular/forms';
import {
  BadgeComponent,
  ButtonDirective,
  ColComponent,
  FormCheckComponent,
  FormCheckInputDirective,
  FormCheckLabelDirective,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  FormTextDirective,
  RowComponent,
} from '@coreui/angular';
import { startWith } from 'rxjs';

import { type CoverageProfile, listCatalogCoverageProfiles, type Usage } from '../../../shared/api';
import { controlMessage } from '../../../shared/ui/forms/form-errors';
import { MoneyPipe } from '../../../shared/ui/pipes';
import { type ApiError, apiData, errorMessage, toApiError } from '../../../shared/util/api-error';
import { USAGE_OPTIONS, USAGES } from '../quote-presentation';
import { CatalogSelectComponent } from './catalog-select.component';
import type { QuoteFieldPath, QuoteForm, QuoteFormLabels } from './quote-form';

/** Moeda das importâncias seguradas do catálogo do laboratório, que o contrato não informa no perfil. */
// WHY: cópia pendente; saída: moeda no `CoverageProfile` do contrato, como a `currency` da oferta.
const CATALOG_CURRENCY = 'BRL';

/**
 * Campos do pedido de cotação (PRD §6): produto fixo, referências do catálogo por rótulo, uso, perfil de cobertura
 * com o resumo das coberturas e o início de vigência. A tela que usa o componente guarda o `<form>`, a revisão e o
 * envio; os campos com pendência da versão anterior ganham destaque.
 */
@Component({
  selector: 'app-quote-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    BadgeComponent,
    ButtonDirective,
    CatalogSelectComponent,
    ColComponent,
    FormCheckComponent,
    FormCheckInputDirective,
    FormCheckLabelDirective,
    FormControlDirective,
    FormLabelDirective,
    FormSelectDirective,
    FormTextDirective,
    MoneyPipe,
    ReactiveFormsModule,
    RowComponent,
  ],
  template: `
    <fieldset [disabled]="disabled()" class="border-0 p-0 m-0">
      <legend class="visually-hidden">Dados da cotação</legend>
      <c-row class="g-3">
        <c-col md="6" xs="12">
          <label cLabel for="quote-product">Produto</label>
          <input cFormControl id="quote-product" readonly type="text" value="Seguro auto" />
          <div cFormText>O MVP cota apenas seguro auto.</div>
        </c-col>
        <c-col md="6" xs="12" [class.pending-field]="isPending('applicantRef')">
          <app-catalog-select
            (labelChange)="setLabel('applicantRef', $event)"
            [control]="form().controls.applicantRef"
            [describedBy]="describe('applicantRef')"
            [invalid]="!!message('applicantRef')"
            [searchable]="true"
            [selectedLabel]="initialLabels()['applicantRef'] ?? null"
            fieldId="quote-applicantRef"
            kind="customers"
            label="Proponente"
            placeholder="Escolha o proponente"
          />
          @if (message('applicantRef'); as text) {
            <div class="invalid-feedback d-block" id="quote-applicantRef-error">{{ text }}</div>
          }
          @if (isPending('applicantRef')) {
            <div class="small text-warning-emphasis" id="quote-applicantRef-pending">Pendência apontada nesta versão.</div>
          }
        </c-col>
        <c-col md="6" xs="12" [class.pending-field]="isPending('risk.vehicleRef')">
          <app-catalog-select
            (labelChange)="setLabel('risk.vehicleRef', $event)"
            [control]="form().controls.risk.controls.vehicleRef"
            [describedBy]="describe('risk.vehicleRef')"
            [invalid]="!!message('risk.vehicleRef')"
            [searchable]="true"
            [selectedLabel]="initialLabels()['risk.vehicleRef'] ?? null"
            fieldId="quote-risk-vehicleRef"
            kind="vehicles"
            label="Veículo"
            placeholder="Escolha o veículo"
          />
          @if (message('risk.vehicleRef'); as text) {
            <div class="invalid-feedback d-block" id="quote-risk-vehicleRef-error">{{ text }}</div>
          }
          @if (isPending('risk.vehicleRef')) {
            <div class="small text-warning-emphasis" id="quote-risk-vehicleRef-pending">Pendência apontada nesta versão: escolha outro veículo ou confira o cadastro.</div>
          }
        </c-col>
        <c-col md="6" xs="12" [class.pending-field]="isPending('risk.principalDriverRef')">
          <app-catalog-select
            (labelChange)="setLabel('risk.principalDriverRef', $event)"
            [control]="form().controls.risk.controls.principalDriverRef"
            [describedBy]="describe('risk.principalDriverRef')"
            [invalid]="!!message('risk.principalDriverRef')"
            [selectedLabel]="initialLabels()['risk.principalDriverRef'] ?? null"
            fieldId="quote-risk-principalDriverRef"
            kind="drivers"
            label="Condutor principal"
            placeholder="Escolha o condutor"
          />
          @if (message('risk.principalDriverRef'); as text) {
            <div class="invalid-feedback d-block" id="quote-risk-principalDriverRef-error">{{ text }}</div>
          }
          @if (isPending('risk.principalDriverRef')) {
            <div class="small text-warning-emphasis" id="quote-risk-principalDriverRef-pending">Pendência apontada nesta versão: escolha outro condutor ou confira o cadastro.</div>
          }
        </c-col>
        <c-col md="6" xs="12">
          <fieldset
            [attr.aria-describedby]="describe('risk.usage')"
            [attr.aria-invalid]="!!message('risk.usage')"
            class="border-0 p-0 m-0"
            id="quote-risk-usage"
            tabindex="-1"
          >
            <legend class="form-label fs-6 mb-2">Uso do veículo</legend>
            @for (usage of usages; track usage) {
              <c-form-check [inline]="true">
                <input
                  [formControl]="form().controls.risk.controls.usage"
                  [id]="'quote-usage-' + usage"
                  [value]="usage"
                  cFormCheckInput
                  name="quote-usage"
                  type="radio"
                />
                <label [for]="'quote-usage-' + usage" cFormCheckLabel>{{ usageLabel(usage) }}</label>
              </c-form-check>
            }
          </fieldset>
          @if (message('risk.usage'); as text) {
            <div class="invalid-feedback d-block" id="quote-risk-usage-error">{{ text }}</div>
          }
        </c-col>
        <c-col md="6" xs="12">
          <label cLabel for="quote-desiredStartDate">Início de vigência desejado</label>
          <input
            [attr.aria-describedby]="describe('desiredStartDate', 'quote-desiredStartDate-help')"
            [attr.aria-invalid]="!!message('desiredStartDate')"
            [formControl]="form().controls.desiredStartDate"
            [valid]="message('desiredStartDate') ? false : undefined"
            cFormControl
            id="quote-desiredStartDate"
            type="date"
          />
          <div cFormText id="quote-desiredStartDate-help">A seguradora confirma se a data é aceita ao cotar.</div>
          @if (message('desiredStartDate'); as text) {
            <div class="invalid-feedback d-block" id="quote-desiredStartDate-error">{{ text }}</div>
          }
        </c-col>
        <c-col xs="12">
          <label cLabel for="quote-coverageProfile">Perfil de cobertura</label>
          <select
            [attr.aria-describedby]="describe('coverageProfile', 'quote-coverageProfile-summary')"
            [attr.aria-invalid]="!!message('coverageProfile')"
            [formControl]="form().controls.coverageProfile"
            [valid]="message('coverageProfile') ? false : undefined"
            cSelect
            id="quote-coverageProfile"
          >
            <option [ngValue]="null">{{ profilesLoading() ? 'Carregando perfis…' : 'Escolha o perfil' }}</option>
            @for (profile of profileOptions(); track profile.code) {
              <option [ngValue]="profile.code">{{ profile.label }}</option>
            }
          </select>
          @if (message('coverageProfile'); as text) {
            <div class="invalid-feedback d-block" id="quote-coverageProfile-error">{{ text }}</div>
          }
          <div aria-live="polite" class="small mt-1" id="quote-coverageProfile-summary">
            @if (profilesError(); as failure) {
              <span class="text-danger">Não foi possível carregar os perfis. {{ errorText(failure) }}</span>
              <button (click)="loadProfiles()" cButton class="p-0 ms-1 align-baseline" color="link" size="sm" type="button">Tentar de novo</button>
            } @else if (!profilesLoading() && !profiles().length) {
              <span class="text-body-secondary">Nenhum perfil de cobertura disponível agora.</span>
              <button (click)="loadProfiles()" cButton class="p-0 ms-1 align-baseline" color="link" size="sm" type="button">Atualizar</button>
            } @else if (selectedProfile(); as profile) {
              @if (profile.summary) {
                <p class="mb-1">{{ profile.summary }}</p>
              }
              <ul class="list-unstyled mb-0">
                @for (coverage of profile.coverages; track coverage.code) {
                  <li>
                    <c-badge class="me-1" color="light" textColor="dark">{{ coverage.label }}</c-badge>
                    importância segurada de referência {{ coverage.insuredAmount | money: currency }}
                  </li>
                }
              </ul>
            }
          </div>
        </c-col>
      </c-row>
    </fieldset>
  `,
  styles: `
    .pending-field {
      border-inline-start: 3px solid var(--cui-warning);
    }
  `,
})
export class QuoteFormComponent implements OnInit {
  readonly form = input.required<QuoteForm>();
  /** Mostra os erros de todos os campos, como depois de tentar enviar. */
  readonly submitted = input(false);
  readonly disabled = input(false);
  /** Rótulos das escolhas atuais, para as opções aparecerem antes de o catálogo carregar. */
  readonly initialLabels = input<QuoteFormLabels>({});
  /** Campos com pendência na versão anterior, pelo caminho do contrato. */
  readonly pendingPaths = input<readonly string[]>([]);

  /** Rótulos das escolhas, para a revisão. */
  readonly labelsChange = output<QuoteFormLabels>();

  readonly usages = USAGE_OPTIONS;
  readonly currency = CATALOG_CURRENCY;
  readonly profiles = signal<readonly CoverageProfile[]>([]);
  readonly profilesLoading = signal(false);
  readonly profilesError = signal<ApiError | null>(null);
  readonly #labels = signal<QuoteFormLabels>({});
  readonly #status = signal(0);

  readonly #profileCode = signal<string | null>(null);
  readonly selectedProfile = computed(
    () => this.profiles().find((profile) => profile.code === this.#profileCode()) ?? null,
  );
  /** Perfis do catálogo, com o perfil atual da versão quando ele saiu do catálogo. */
  readonly profileOptions = computed(() => {
    const code = this.#profileCode();
    const label = this.initialLabels().coverageProfile;
    const profiles = this.profiles();
    return code && label && !profiles.some((profile) => profile.code === code)
      ? [{ code, label, coverages: [] }, ...profiles]
      : profiles;
  });

  constructor() {
    effect((onCleanup) => {
      const form = this.form();
      const coverage = form.controls.coverageProfile;
      const subscriptions = [
        coverage.valueChanges
          .pipe(startWith(coverage.value))
          .subscribe((code) => this.#profileCode.set(code)),
        // NOTE: `events` traz também o toque no campo, que não passa por `statusChanges` nem `valueChanges`.
        form.events.subscribe(() => this.#status.update((value) => value + 1)),
      ];
      onCleanup(() => subscriptions.forEach((subscription) => subscription.unsubscribe()));
    });
    effect(() => {
      const labels = {
        ...this.#labels(),
        coverageProfile:
          this.selectedProfile()?.label ?? this.initialLabels().coverageProfile ?? null,
      };
      untracked(() => this.labelsChange.emit(labels));
    });
  }

  ngOnInit(): void {
    void this.loadProfiles();
  }

  async loadProfiles(): Promise<void> {
    this.profilesLoading.set(true);
    this.profilesError.set(null);
    try {
      const fetchPage = async (
        cursor: string | null,
        items: CoverageProfile[] = [],
      ): Promise<CoverageProfile[]> => {
        const page = await apiData(
          listCatalogCoverageProfiles({ query: cursor ? { cursor, limit: 100 } : { limit: 100 } }),
        );
        const nextItems = [...items, ...page.items];
        return page.nextCursor ? fetchPage(page.nextCursor, nextItems) : nextItems;
      };
      this.profiles.set(await fetchPage(null));
    } catch (error_) {
      this.profilesError.set(toApiError(error_));
    } finally {
      this.profilesLoading.set(false);
    }
  }

  setLabel(path: QuoteFieldPath, label: string | null): void {
    this.#labels.update((labels) =>
      labels[path] === label ? labels : { ...labels, [path]: label },
    );
  }

  usageLabel(usage: Usage): string {
    return USAGES[usage];
  }

  isPending(path: QuoteFieldPath): boolean {
    return this.pendingPaths().includes(path);
  }

  /** Mensagem do campo depois de tocado ou de tentar enviar; recalcula a cada mudança do formulário. */
  message(path: QuoteFieldPath): string | null {
    this.#status();
    const control: AbstractControl | null = this.form().get(path);
    return control && (this.submitted() || control.touched) ? controlMessage(control) : null;
  }

  describe(path: QuoteFieldPath, help?: string): string | null {
    const id = `quote-${path.replace('.', '-')}`;
    const parts = [
      help ?? null,
      this.message(path) ? `${id}-error` : null,
      this.isPending(path) ? `${id}-pending` : null,
    ].filter((part): part is string => part !== null);
    return parts.length ? parts.join(' ') : null;
  }

  errorText(error: ApiError): string {
    return errorMessage(error);
  }
}
