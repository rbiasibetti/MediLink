import { TestBed } from '@angular/core/testing';
import { MedicoAuth } from './medico-auth';

describe('MedicoAuth', () => {
  let auth: MedicoAuth;
  beforeEach(() => { auth = TestBed.inject(MedicoAuth); });

  it('começa deslogado e sem cabeçalho', () => {
    expect(auth.logado()).toBe(false);
    expect(auth.headers()).toEqual({});
  });

  it('gera o Basic com usuário e senha (UTF-8)', () => {
    auth.definir('dra@clinica.test', 'sênha-ç');
    const esperado = btoa(String.fromCharCode(...new TextEncoder().encode('dra@clinica.test:sênha-ç')));
    expect(auth.headers()).toEqual({ Authorization: `Basic ${esperado}` });
    expect(auth.logado()).toBe(true);
  });

  it('sair limpa credenciais e nome', () => {
    auth.definir('a@b.c', 'x');
    auth.confirmar('Dra. Ana');
    auth.sair();
    expect(auth.logado()).toBe(false);
    expect(auth.nome()).toBeNull();
    expect(auth.headers()).toEqual({});
  });
});
