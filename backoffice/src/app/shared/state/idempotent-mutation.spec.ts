import { HttpErrorResponse } from '@angular/common/http';

import { type AttemptStore, IdempotentMutation, type MutationAttempt } from './idempotent-mutation';

interface Body {
  readonly offerId: string;
}

function memoryStore(initial: MutationAttempt<Body> | null = null): AttemptStore<Body> & {
  value: MutationAttempt<Body> | null;
} {
  return {
    value: initial,
    load() {
      return this.value;
    },
    save(attempt) {
      this.value = attempt;
    },
    clear() {
      this.value = null;
    },
  };
}

function keys(): () => string {
  let next = 0;
  return () => `key-${++next}`;
}

describe('IdempotentMutation', () => {
  it('duplo clique durante o envio vira uma intenção só', async () => {
    let release!: (value: string) => void;
    const send = vi.fn(() => new Promise<string>((resolve) => (release = resolve)));
    const mutation = new IdempotentMutation<Body, string>({ send, newKey: keys() });

    const first = mutation.submit({ offerId: 'a' });
    const second = mutation.submit({ offerId: 'a' });
    release('ok');

    await expect(first).resolves.toBe('ok');
    await expect(second).resolves.toBeNull();
    expect(send).toHaveBeenCalledTimes(1);
    expect(mutation.state()).toBe('succeeded');
  });

  it('depois de um timeout, trava o envio e só a verificação reenvia a mesma chave e o mesmo corpo', async () => {
    const send = vi
      .fn<(key: string, body: Body) => Promise<string>>()
      .mockRejectedValueOnce(Object.assign(new Error('timeout'), { name: 'TimeoutError' }))
      .mockResolvedValueOnce('ok');
    const store = memoryStore();
    const mutation = new IdempotentMutation<Body, string>({ send, store, newKey: keys() });

    await mutation.submit({ offerId: 'a' });
    expect(mutation.state()).toBe('unknown');
    expect(mutation.locked()).toBe(true);
    expect(store.value?.key).toBe('key-1');

    await expect(mutation.submit({ offerId: 'b' })).resolves.toBeNull();
    await expect(mutation.retry()).resolves.toBe('ok');

    expect(send.mock.calls).toEqual([
      ['key-1', { offerId: 'a' }],
      ['key-1', { offerId: 'a' }],
    ]);
    expect(store.value).toBeNull();
  });

  it('recupera da sessão a tentativa sem resposta, para a tela recarregada verificar o envio', async () => {
    const pending = { key: 'saved', body: { offerId: 'a' }, startedAt: '2026-11-12T10:00:00Z' };
    const send = vi.fn().mockResolvedValue('ok');
    const mutation = new IdempotentMutation<Body, string>({ send, store: memoryStore(pending) });

    expect(mutation.state()).toBe('unknown');
    await mutation.retry();

    expect(send).toHaveBeenCalledWith('saved', { offerId: 'a' });
  });

  it('depois do 503, o mesmo corpo reusa a chave e outro corpo é outra intenção', async () => {
    const send = vi
      .fn<(key: string, body: Body) => Promise<string>>()
      .mockRejectedValueOnce(
        new HttpErrorResponse({ status: 503, error: { code: 'SERVICE_UNAVAILABLE' } }),
      )
      .mockRejectedValueOnce(
        new HttpErrorResponse({ status: 503, error: { code: 'SERVICE_UNAVAILABLE' } }),
      )
      .mockResolvedValueOnce('ok');
    const mutation = new IdempotentMutation<Body, string>({ send, newKey: keys() });

    await mutation.submit({ offerId: 'a' });
    expect(mutation.state()).toBe('retryable');
    expect(mutation.locked()).toBe(false);
    await mutation.submit({ offerId: 'a' });
    await mutation.submit({ offerId: 'b' });

    expect(send.mock.calls.map(([key]) => key)).toEqual(['key-1', 'key-1', 'key-2']);
  });

  it('depois de uma recusa definitiva, a próxima tentativa recebe chave nova', async () => {
    const send = vi
      .fn<(key: string, body: Body) => Promise<string>>()
      .mockRejectedValueOnce(
        new HttpErrorResponse({ status: 409, error: { code: 'OFFER_EXPIRED' } }),
      )
      .mockResolvedValueOnce('ok');
    const store = memoryStore();
    const mutation = new IdempotentMutation<Body, string>({ send, store, newKey: keys() });

    await mutation.submit({ offerId: 'a' });
    expect(mutation.state()).toBe('rejected');
    expect(mutation.error()?.code).toBe('OFFER_EXPIRED');
    expect(store.value).toBeNull();
    await mutation.submit({ offerId: 'a' });

    expect(send.mock.calls.map(([key]) => key)).toEqual(['key-1', 'key-2']);
  });

  it('não descarta um resultado desconhecido', async () => {
    const send = vi.fn().mockRejectedValue(new HttpErrorResponse({ status: 0 }));
    const mutation = new IdempotentMutation<Body, string>({ send, newKey: keys() });

    await mutation.submit({ offerId: 'a' });
    mutation.reset();

    expect(mutation.state()).toBe('unknown');
  });
});
