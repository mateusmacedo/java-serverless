import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { body } from '../../../../testing/fixtures';
import { byPath, provideTestApi, respond, settle } from '../../../../testing/http';
import type { QuoteAccepted } from '../../../shared/api';
import { SessionScopedStorage } from '../../../shared/state/session-storage';
import { NewQuotePageComponent } from './new-quote-page.component';

describe('NewQuotePageComponent', () => {
  let fixture: ComponentFixture<NewQuotePageComponent>;
  let controller: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    sessionStorage.clear();
    sessionStorage.setItem(
      'backoffice.auth',
      JSON.stringify({ accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 3_600_000 }),
    );
    TestBed.configureTestingModule({ providers: [provideTestApi(), provideRouter([])] });
    TestBed.inject(SessionScopedStorage).usePartition('ana', 'org-acme');
    controller = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(NewQuotePageComponent);
    fixture.autoDetectChanges();
    await answerCatalogs();
  });

  afterEach(() => controller.verify());

  async function answerCatalogs(): Promise<void> {
    await settle();
    respond(controller.expectOne(byPath('/v1/catalog/customers')), 'catalogPages', 'clientes');
    respond(
      controller.expectOne(byPath('/v1/catalog/vehicles')),
      'catalogPages',
      'veículos com busca',
    );
    respond(controller.expectOne(byPath('/v1/catalog/drivers')), 'catalogPages', 'condutores');
    respond(
      controller.expectOne(byPath('/v1/catalog/coverage-profiles')),
      'catalogPages',
      'perfis de cobertura',
    );
    await settle();
  }

  function button(label: string): HTMLButtonElement {
    const found = Array.from(
      fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
    ).find((item) => item.textContent?.trim() === label);
    if (!found) {
      throw new Error(`Botão "${label}" não encontrado`);
    }
    return found;
  }

  function fillValidForm(): void {
    fixture.componentInstance.form.setValue({
      applicantRef: 'customer-acme-ana',
      risk: {
        vehicleRef: 'vehicle-hatch-2021',
        usage: 'PERSONAL',
        principalDriverRef: 'driver-acme-ana',
      },
      coverageProfile: 'COMPREHENSIVE',
      desiredStartDate: '2026-12-01',
    });
  }

  it('com campos inválidos, não chama a API e leva o foco ao primeiro campo (FT02)', async () => {
    button('Revisar solicitação').click();
    await settle();

    controller.expectNone((r) => r.method === 'POST');
    expect(document.activeElement?.id).toBe('quote-applicantRef');
    expect(fixture.nativeElement.textContent).toContain('Preencha este campo.');
  });

  it('o duplo clique envia uma intenção só, e o timeout é recuperado com a mesma chave e o mesmo corpo (FT03)', async () => {
    fillValidForm();
    button('Revisar solicitação').click();
    await settle();
    expect(fixture.nativeElement.textContent).toContain('Não é uma contratação');

    const send = button('Enviar solicitação');
    send.click();
    send.click();
    await settle();
    const first = controller.expectOne(byPath('/v1/quotes', 'POST'));
    const key = first.request.headers.get('Idempotency-Key');
    const sentBody = first.request.body;
    first.error(new ProgressEvent('error'), { status: 0 });
    await settle();

    expect(fixture.nativeElement.textContent).toContain('Não recebemos a confirmação do servidor.');
    expect(button('Voltar e editar').disabled).toBe(true);
    expect(fixture.componentInstance.form.disabled).toBe(true);

    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    button('Verificar envio').click();
    await settle();
    const retry = controller.expectOne(byPath('/v1/quotes', 'POST'));
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual(sentBody);
    const accepted = body<QuoteAccepted>('quoteProgress', 'aceitação durável');
    retry.flush(accepted, { status: 202, statusText: 'Accepted' });
    await settle();

    expect(navigate).toHaveBeenCalledWith([
      '/quotes',
      accepted.quoteId,
      'versions',
      accepted.version,
    ]);
  });

  it('mostra o erro do servidor no campo do 400', async () => {
    fillValidForm();
    button('Revisar solicitação').click();
    await settle();
    button('Enviar solicitação').click();
    await settle();
    respond(
      controller.expectOne((r) => r.method === 'POST'),
      'errors',
      'validação',
    );
    // NOTE: o formulário volta à tela e lê o catálogo de novo.
    await answerCatalogs();

    expect(
      fixture.nativeElement.querySelector('#quote-desiredStartDate-error')?.textContent,
    ).toContain('Use uma data válida no formato YYYY-MM-DD.');
    expect(fixture.nativeElement.querySelector('#quote-risk-usage-error')?.textContent).toContain(
      'Use PERSONAL ou COMMERCIAL.',
    );
  });
});
