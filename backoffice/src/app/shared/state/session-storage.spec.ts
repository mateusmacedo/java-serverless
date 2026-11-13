import { TestBed } from '@angular/core/testing';

import { BROWSER_STORAGE, SessionScopedStorage } from './session-storage';

describe('SessionScopedStorage', () => {
  let storage: Storage;
  let scoped: SessionScopedStorage;

  beforeEach(() => {
    TestBed.resetTestingModule();
    storage = window.sessionStorage;
    storage.clear();
    TestBed.configureTestingModule({
      providers: [{ provide: BROWSER_STORAGE, useValue: storage }],
    });
    scoped = TestBed.inject(SessionScopedStorage);
  });

  it('não lê nem grava antes de saber o usuário', () => {
    scoped.write('attempt.create-quote', { key: 'k' });

    expect(scoped.read('attempt.create-quote')).toBeNull();
    expect(storage).toHaveLength(0);
  });

  it('separa os dados por usuário e organização e apaga os de outro usuário da mesma aba', () => {
    scoped.usePartition('ana', 'org-acme');
    scoped.write('attempt.create-quote', { key: 'da-ana' });

    scoped.usePartition('olga', 'org-acme');
    expect(scoped.read('attempt.create-quote')).toBeNull();

    scoped.usePartition('ana', 'org-acme');
    expect(scoped.read('attempt.create-quote')).toBeNull();
  });

  it('a saída apaga tudo o que o painel guardou, inclusive os tokens', () => {
    storage.setItem('backoffice.auth', '{}');
    storage.setItem('outra-app', 'fica');
    scoped.usePartition('ana', 'org-acme');
    scoped.write('operations.tracked', []);

    scoped.clearAll();

    expect(storage.getItem('backoffice.auth')).toBeNull();
    expect(storage.getItem('outra-app')).toBe('fica');
    expect(storage).toHaveLength(1);
  });
});
