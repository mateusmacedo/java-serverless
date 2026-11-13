import { Poller, type PollOutcome } from './poller';

function fakeDocument(): Document & { hide(): void; show(): void } {
  const target = new EventTarget() as Document & { hide(): void; show(): void };
  let hidden = false;
  Object.defineProperty(target, 'hidden', { get: () => hidden });
  target.hide = () => {
    hidden = true;
    target.dispatchEvent(new Event('visibilitychange'));
  };
  target.show = () => {
    hidden = false;
    target.dispatchEvent(new Event('visibilitychange'));
  };
  return target;
}

describe('Poller', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('agenda a próxima rodada pelo intervalo da resposta e para no fim', async () => {
    const outcomes: PollOutcome[] = [5, 3, null];
    const tick = vi.fn(async () => outcomes.shift() ?? null);
    const poller = new Poller({ tick, retrySeconds: 3, document: fakeDocument() });

    await poller.start();
    await vi.advanceTimersByTimeAsync(4_999);
    expect(tick).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(tick).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3_000);
    expect(tick).toHaveBeenCalledTimes(3);
    await vi.advanceTimersByTimeAsync(60_000);

    expect(tick).toHaveBeenCalledTimes(3);
    expect(poller.finished).toBe(true);
  });

  it('não sobrepõe rodadas: o "Atualizar" durante uma leitura espera a mesma', async () => {
    let release!: (value: PollOutcome) => void;
    const tick = vi.fn(() => new Promise<PollOutcome>((resolve) => (release = resolve)));
    const poller = new Poller({ tick, retrySeconds: 3, document: fakeDocument() });

    const started = poller.start();
    const refreshed = poller.refresh();
    release(null);
    await Promise.all([started, refreshed]);

    expect(tick).toHaveBeenCalledTimes(1);
  });

  it('suspende com a aba oculta e lê na hora ao voltar, sem duplicar o laço', async () => {
    const document = fakeDocument();
    const tick = vi.fn(async (): Promise<PollOutcome> => 3);
    const poller = new Poller({ tick, retrySeconds: 3, document });

    await poller.start();
    document.hide();
    await vi.advanceTimersByTimeAsync(30_000);
    expect(tick).toHaveBeenCalledTimes(1);

    document.show();
    await vi.advanceTimersByTimeAsync(0);
    expect(tick).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(3_000);

    expect(tick).toHaveBeenCalledTimes(3);
    poller.stop();
  });

  it('numa falha, tenta de novo com recuo até o teto', async () => {
    const tick = vi.fn(async (): Promise<PollOutcome> => {
      throw new Error('rede');
    });
    const poller = new Poller({
      tick,
      retrySeconds: 3,
      maxRetrySeconds: 10,
      document: fakeDocument(),
    });

    await poller.start();
    await vi.advanceTimersByTimeAsync(3_000);
    await vi.advanceTimersByTimeAsync(6_000);
    await vi.advanceTimersByTimeAsync(10_000);

    expect(tick).toHaveBeenCalledTimes(4);
    poller.stop();
  });

  it('depois de parado, não agenda mais nada', async () => {
    const tick = vi.fn(async (): Promise<PollOutcome> => 3);
    const poller = new Poller({ tick, retrySeconds: 3, document: fakeDocument() });

    await poller.start();
    poller.stop();
    await vi.advanceTimersByTimeAsync(30_000);

    expect(tick).toHaveBeenCalledTimes(1);
  });
});
