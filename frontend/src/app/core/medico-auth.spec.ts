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

  it('sessão por link: Bearer com prioridade sobre o Basic, guardada na aba e descartada ao sair', () => {
    sessionStorage.clear();
    auth.definir('a@b.c', 'x');
    auth.definirSessao('TOKEN-DA-SESSAO', 'Dra. Ana');

    expect(auth.logado()).toBe(true);
    expect(auth.nome()).toBe('Dra. Ana');
    expect(auth.headers()).toEqual({ Authorization: 'Bearer TOKEN-DA-SESSAO' });
    expect(sessionStorage.getItem('medilink.sessao-medico')).toBe('TOKEN-DA-SESSAO');

    auth.sair();
    expect(auth.headers()).toEqual({});
    expect(sessionStorage.getItem('medilink.sessao-medico')).toBeNull();
  });

  it('recarregar a página na mesma aba recupera a sessão', () => {
    sessionStorage.setItem('medilink.sessao-medico', 'TOKEN-GUARDADO');
    TestBed.resetTestingModule();

    const nova = TestBed.inject(MedicoAuth);

    expect(nova.logado()).toBe(true);
    expect(nova.headers()).toEqual({ Authorization: 'Bearer TOKEN-GUARDADO' });
    sessionStorage.clear();
  });
});
