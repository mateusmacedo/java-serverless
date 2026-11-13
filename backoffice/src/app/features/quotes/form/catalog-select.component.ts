import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { type FormControl, ReactiveFormsModule } from '@angular/forms';
import {
  ButtonDirective,
  FormControlDirective,
  FormLabelDirective,
  FormSelectDirective,
  SpinnerComponent,
} from '@coreui/angular';

import {
  type CatalogItem,
  type CatalogPage,
  listCatalogCustomers,
  listCatalogDrivers,
  listCatalogVehicles,
} from '../../../shared/api';
import { type ApiError, apiData, errorMessage, toApiError } from '../../../shared/util/api-error';

/** Catálogo do laboratório que o campo usa. */
export type CatalogKind = 'customers' | 'vehicles' | 'drivers';

type CatalogFetcher = (query: { search?: string; cursor?: string }) => Promise<CatalogPage>;

const FETCHERS: Readonly<Record<CatalogKind, CatalogFetcher>> = {
  customers: (query) => apiData(listCatalogCustomers({ query })),
  vehicles: (query) => apiData(listCatalogVehicles({ query })),
  drivers: (query) => apiData(listCatalogDrivers({ query })),
};

/** Espera da digitação antes de buscar no servidor. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * Campo de referência do catálogo: a busca no servidor (`search`, por rótulo ou id) e a lista de opções por rótulo,
 * com "Carregar mais" pelo cursor. O valor é sempre um id que veio do catálogo; o campo não aceita id digitado. O
 * catálogo vazio explica a falta de opções e oferece atualizar.
 */
@Component({
  selector: 'app-catalog-select',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ButtonDirective,
    FormControlDirective,
    FormLabelDirective,
    FormSelectDirective,
    ReactiveFormsModule,
    SpinnerComponent,
  ],
  template: `
    <label [for]="fieldId()" cLabel>{{ label() }}</label>
    @if (searchable()) {
      <input
        (input)="typed($event)"
        [attr.aria-label]="'Buscar ' + label().toLocaleLowerCase('pt-BR')"
        [attr.aria-controls]="fieldId()"
        [disabled]="control().disabled"
        [id]="fieldId() + '-search'"
        [value]="search()"
        autocomplete="off"
        cFormControl
        class="mb-1"
        maxlength="100"
        placeholder="Buscar por nome"
        sizing="sm"
        type="search"
      />
    }
    <select
      [attr.aria-describedby]="describedBy()"
      [attr.aria-invalid]="invalid()"
      [attr.aria-busy]="loading()"
      [formControl]="control()"
      [id]="fieldId()"
      [valid]="invalid() ? false : undefined"
      cSelect
    >
      <option [ngValue]="null">{{ placeholder() }}</option>
      @for (item of options(); track item.id) {
        <option [ngValue]="item.id">{{ item.label }}</option>
      }
    </select>
    <div aria-live="polite" class="small mt-1">
      @if (loading()) {
        <span class="text-body-secondary"><c-spinner aria-hidden="true" class="me-1" label="" size="sm" />Carregando opções…</span>
      } @else if (error(); as failure) {
        <span class="text-danger">{{ errorText(failure) }}</span>
        <button (click)="reload()" cButton class="p-0 ms-1 align-baseline" color="link" size="sm" type="button">Tentar de novo</button>
      } @else if (!items().length && search()) {
        <span class="text-body-secondary">Nenhuma opção encontrada para “{{ search() }}”.</span>
      } @else if (!items().length) {
        <span class="text-body-secondary">Nenhuma opção disponível no catálogo da sua organização.</span>
        <button (click)="reload()" cButton class="p-0 ms-1 align-baseline" color="link" size="sm" type="button">Atualizar</button>
      } @else if (nextCursor()) {
        <button (click)="loadMore()" [disabled]="control().disabled" cButton class="p-0 align-baseline" color="link" size="sm" type="button">
          Carregar mais opções
        </button>
      }
    </div>
  `,
})
export class CatalogSelectComponent {
  readonly kind = input.required<CatalogKind>();
  readonly label = input.required<string>();
  readonly fieldId = input.required<string>();
  readonly control = input.required<FormControl<string | null>>();
  readonly searchable = input(false);
  readonly invalid = input(false);
  readonly describedBy = input<string | null>(null);
  readonly placeholder = input('Escolha uma opção');
  /** Rótulo da opção já escolhida, para mantê-la na lista quando a busca não a traz (complementação). */
  readonly selectedLabel = input<string | null>(null);

  /** Rótulo da opção escolhida, para a revisão do pedido. */
  readonly labelChange = output<string | null>();

  readonly items = signal<readonly CatalogItem[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly error = signal<ApiError | null>(null);
  readonly search = signal('');
  readonly #value = signal<string | null>(null);

  /** Opções da página, com a já escolhida no topo quando ela não veio na busca. */
  readonly options = computed(() => {
    const items = this.items();
    const value = this.#value();
    const label = this.selectedLabel();
    return value && label && !items.some((item) => item.id === value)
      ? [{ id: value, label }, ...items]
      : items;
  });

  #timer: ReturnType<typeof setTimeout> | null = null;
  #request = 0;

  constructor() {
    effect(() => {
      const control = this.control();
      untracked(() => {
        this.#value.set(control.value);
        void this.#load(false);
      });
    });
    effect((onCleanup) => {
      const control = this.control();
      const subscription = control.valueChanges.subscribe((value) => this.#value.set(value));
      onCleanup(() => subscription.unsubscribe());
    });
    effect(() => {
      this.labelChange.emit(this.#labelOf(this.#value()));
    });
    inject(DestroyRef).onDestroy(() => this.#cancelTimer());
  }

  typed(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
    this.#cancelTimer();
    this.#timer = setTimeout(() => void this.#load(false), SEARCH_DEBOUNCE_MS);
  }

  reload(): void {
    void this.#load(false);
  }

  loadMore(): void {
    void this.#load(true);
  }

  errorText(error: ApiError): string {
    return `Não foi possível carregar as opções. ${errorMessage(error)}`;
  }

  async #load(more: boolean): Promise<void> {
    const request = ++this.#request;
    const search = this.search().trim();
    const cursor = more ? this.nextCursor() : null;
    this.loading.set(true);
    this.error.set(null);
    try {
      const page = await FETCHERS[this.kind()]({
        ...(search ? { search } : {}),
        ...(cursor ? { cursor } : {}),
      });
      if (request !== this.#request) {
        return;
      }
      this.items.update((items) => (more ? [...items, ...page.items] : page.items));
      this.nextCursor.set(page.nextCursor ?? null);
    } catch (error_) {
      if (request === this.#request) {
        this.error.set(toApiError(error_));
      }
    } finally {
      if (request === this.#request) {
        this.loading.set(false);
      }
    }
  }

  #labelOf(value: string | null): string | null {
    if (!value) {
      return null;
    }
    return this.options().find((item) => item.id === value)?.label ?? this.selectedLabel();
  }

  #cancelTimer(): void {
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
      this.#timer = null;
    }
  }
}
