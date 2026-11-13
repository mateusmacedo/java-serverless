import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  ViewEncapsulation,
} from '@angular/core';
import {
  FormLabelDirective,
  FormSelectDirective,
  PageItemDirective,
  PageLinkDirective,
  PaginationComponent,
} from '@coreui/angular';

import {
  back,
  canGoBack,
  type CursorTrail,
  DEFAULT_PAGE_SIZE,
  followCursor,
  forward,
  PAGE_SIZES,
  startTrail,
  toPageSize,
} from '../../util/cursor';

let nextId = 0;

/**
 * Paginação por cursor sobre o `c-pagination` do template, só com Anterior e Próxima, e o seletor de 20, 50 ou 100
 * itens. Guarda a pilha dos cursores visitados para voltar; a lista guarda o cursor e o tamanho na URL e recebe as
 * mudanças pelos eventos.
 */
@Component({
  selector: 'app-cursor-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // WHY: a margem do `ul` vem do reboot e fica dentro do template do `c-pagination`, fora do alcance do estilo
  // encapsulado.
  encapsulation: ViewEncapsulation.None,
  styles: 'app-cursor-pagination .pagination { margin-bottom: 0; }',
  imports: [
    FormLabelDirective,
    FormSelectDirective,
    PageItemDirective,
    PageLinkDirective,
    PaginationComponent,
  ],
  template: `
    <div class="d-flex flex-wrap gap-3 align-items-center mt-3">
      <c-pagination aria-label="Páginas">
        <li [disabled]="disabled() || !hasPrevious()" cPageItem>
          <button (click)="goBack()" [attr.disabled]="disabled() || !hasPrevious() ? '' : null" cPageLink type="button">
            Anterior
          </button>
        </li>
        <li [disabled]="disabled() || !hasNext()" cPageItem>
          <button (click)="goForward()" [attr.disabled]="disabled() || !hasNext() ? '' : null" cPageLink type="button">
            Próxima
          </button>
        </li>
      </c-pagination>
      <div class="ms-auto d-flex align-items-center gap-2">
        <label [for]="selectId" cLabel class="text-body-secondary small mb-0">Itens por página</label>
        <select
          (change)="changeLimit($event)"
          [disabled]="disabled()"
          [id]="selectId"
          [value]="pageSize()"
          cSelect
          class="w-auto"
          sizing="sm"
        >
          @for (size of pageSizes; track size) {
            <option [selected]="size === pageSize()" [value]="size">{{ size }}</option>
          }
        </select>
      </div>
    </div>
  `,
})
export class CursorPaginationComponent {
  /** Cursor da página aberta, lido da URL; vazio é a primeira página. */
  readonly cursor = input<string | null | undefined>();
  /** Próximo cursor da resposta da API; ausente na última página. */
  readonly nextCursor = input<string | null | undefined>();
  readonly limit = input<number | string | null | undefined>(DEFAULT_PAGE_SIZE);
  /** Trava os botões e o seletor durante o carregamento. */
  readonly disabled = input(false);

  /** Cursor da página pedida; `undefined` é a primeira página. */
  readonly cursorChange = output<string | undefined>();
  /** Novo tamanho de página; a lista volta para a primeira página. */
  readonly limitChange = output<number>();

  readonly pageSizes = PAGE_SIZES;
  readonly selectId = `app-cursor-pagination-${nextId++}`;
  readonly pageSize = computed(() => toPageSize(this.limit()));

  readonly #trail = linkedSignal<string | null | undefined, CursorTrail>({
    source: this.cursor,
    computation: (cursor, previous) =>
      previous ? followCursor(previous.value, cursor) : startTrail(cursor),
  });

  readonly hasPrevious = computed(() => canGoBack(this.#trail()));
  readonly hasNext = computed(() => !!this.nextCursor());

  goBack(): void {
    if (!this.hasPrevious()) {
      return;
    }
    const trail = back(this.#trail());
    this.#trail.set(trail);
    this.cursorChange.emit(trail.current);
  }

  goForward(): void {
    const next = this.nextCursor();
    if (!next) {
      return;
    }
    const trail = forward(this.#trail(), next);
    this.#trail.set(trail);
    this.cursorChange.emit(trail.current);
  }

  changeLimit(event: Event): void {
    this.limitChange.emit(toPageSize((event.target as HTMLSelectElement).value));
  }
}
