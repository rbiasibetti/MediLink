import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AdminApi, Painel, Termo } from '../core/admin-api';
import { AdminAuth } from '../core/admin-auth';
import { LoginAdmin } from './login-admin';
import { ResumoAdmin } from './resumo-admin';
import { SistemasAdmin } from './sistemas-admin';
import { TermoAdmin } from './termo-admin';

async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}
function digitar(f: { nativeElement: HTMLElement }, seletor: string, valor: string) {
  const el = f.nativeElement.querySelector(seletor) as HTMLInputElement;
  el.value = valor;
  el.dispatchEvent(new Event('input'));
}
function clicar(f: { nativeElement: HTMLElement }, rotulo: string) {
  const b = Array.from(f.nativeElement.querySelectorAll('button')).find((x) => x.textContent?.includes(rotulo));
  if (!b) throw new Error('botão não encontrado: ' + rotulo);
  b.click();
}

describe('AdminAuth', () => {
  it('só em memória: cabeçalho Bearer enquanto logado e nada depois de sair', () => {
    const a = TestBed.inject(AdminAuth);
    expect(a.logado()).toBe(false);
    expect(a.headers()).toEqual({});
    a.entrar('JWT-ADMIN');
    expect(a.headers()).toEqual({ Authorization: 'Bearer JWT-ADMIN' });
    expect(sessionStorage.length + localStorage.length).toBe(0); // a sessão do administrador não vai para storage
    a.sair();
    expect(a.logado()).toBe(false);
  });
});

describe('LoginAdmin', () => {
  function montar(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AdminApi, useValue: api }] });
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { f: TestBed.createComponent(LoginAdmin), navegar };
  }

  it('primeiro acesso mostra o segredo do autenticador e só entra com o código', async () => {
    const api = { login: vi.fn()
      .mockReturnValueOnce(of({ etapa: 'ENROLAR_TOTP', segredoTotp: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x' }))
      .mockReturnValueOnce(of({ etapa: 'OK', token: 'JWT-ADMIN', expiraEm: 'x' })) };
    const { f, navegar } = montar(api);
    await assentar(f);
    digitar(f, '#email', 'admin@clinica.test');
    digitar(f, '#senha', 'uma-senha-bem-longa-123');
    await assentar(f);
    clicar(f, 'Entrar');
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('JBSWY3DPEHPK3PXP');
    expect(navegar).not.toHaveBeenCalled();

    digitar(f, '#codigo', '123456');
    await assentar(f);
    clicar(f, 'Entrar');
    await assentar(f);

    expect(api.login).toHaveBeenLastCalledWith('admin@clinica.test', 'uma-senha-bem-longa-123', '123456');
    expect(TestBed.inject(AdminAuth).headers()).toEqual({ Authorization: 'Bearer JWT-ADMIN' });
    expect(navegar).toHaveBeenCalledWith(['/admin/painel']);
    expect((f.nativeElement.querySelector('#senha') as HTMLInputElement).value).toBe(''); // a senha não fica na tela
  });

  it('falha genérica e bloqueio por excesso de tentativas', async () => {
    const api = { login: vi.fn().mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 403 }))).mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 }))) };
    const { f } = montar(api);
    await assentar(f);
    digitar(f, '#email', 'a@b.test');
    digitar(f, '#senha', 'x'.repeat(12));
    await assentar(f);
    clicar(f, 'Entrar');
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Não foi possível autenticar');
    expect(f.nativeElement.textContent).not.toContain('senha incorreta'); // não diz qual parte falhou
    expect(f.nativeElement.querySelector('#codigo')).not.toBeNull();       // passa a pedir o código

    clicar(f, 'Entrar');
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Muitas tentativas');
  });
});

const painel: Painel = {
  geradoEm: '2026-10-30T12:00:00Z', periodoDias: 30,
  agendamentos: { porStatus: { AGENDADO: 5, CANCELADO: 1 }, criadosNoPeriodo: 6, porSistema: [{ sistema: 'Clínica X', total: 4, noPeriodo: 3 }] },
  consultas: { porStatus: { ENCERRADA: 3 }, encerradasNoPeriodo: 3 },
  transcricao: { jobsPorStatus: { CONCLUIDO: 2, FALHA_NO_WORKER: 1 }, transcricoesGravadas: 2, audioAindaNoDisco: 1, audioMaisAntigoDesde: '2026-10-29T10:00:00Z' },
  webhooks: { ENTREGUE: 2, FALHA: 1 },
  incidentes: [{ consultaId: 'c-1', tipo: 'FALHA_NO_WORKER', desde: '2026-10-30T11:00:00Z', erro: 'RuntimeError' }, { consultaId: 'c-2', tipo: 'SEM_AUDIO', desde: '2026-10-30T11:30:00Z', erro: null }],
};

describe('ResumoAdmin', () => {
  it('mostra contagens e incidentes; só job que falhou pode ser reenviado', async () => {
    const api = { painel: vi.fn(() => of(painel)), reenviarJob: vi.fn(() => of(undefined)) };
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: api }] });
    const f = TestBed.createComponent(ResumoAdmin);
    await assentar(f);

    const texto = f.nativeElement.textContent as string;
    expect(texto).toContain('AGENDADO: 5');
    expect(texto).toContain('Clínica X');
    expect(texto).toContain('c-1');
    expect(texto).toContain('SEM_AUDIO');
    const reenviar = Array.from(f.nativeElement.querySelectorAll('button')).filter((b) => (b as HTMLElement).textContent?.includes('Reenviar'));
    expect(reenviar).toHaveLength(1); // o SEM_AUDIO não tem o que reenviar
    (reenviar[0] as HTMLButtonElement).click();
    await assentar(f);
    expect(api.reenviarJob).toHaveBeenCalledWith('c-1');
  });
});

describe('SistemasAdmin', () => {
  it('a API key e o segredo do webhook aparecem uma vez e somem ao confirmar', async () => {
    const sistema = { id: 's1', nome: 'Clínica X', ativo: true, webhookBaseUrl: null, webhookAtivo: false, webhookTemSegredo: false, criadoEm: 'x' };
    const api = {
      integradores: vi.fn(() => of([sistema])), chaves: vi.fn(() => of([])),
      criarChave: vi.fn(() => of({ id: 'k1', chave: 'mlk_abc_SEGREDO-MOSTRADO-UMA-VEZ' })),
      webhook: vi.fn(() => of({ integrador: sistema, segredo: 'whsec_NOVO' })),
    };
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: api }] });
    const f = TestBed.createComponent(SistemasAdmin);
    await assentar(f);
    clicar(f, 'Abrir');
    await assentar(f);
    digitar(f, 'input[name="nomeKey"]', 'produção');
    await assentar(f);
    clicar(f, 'Gerar key');
    await assentar(f);

    expect(api.criarChave).toHaveBeenCalledWith('s1', 'produção', ['agendamento:escrever', 'agendamento:ler', 'sala:iniciar']);
    expect(f.nativeElement.textContent).toContain('SEGREDO-MOSTRADO-UMA-VEZ');
    expect(f.nativeElement.textContent).toContain('não será mostrado de novo');

    clicar(f, 'Já guardei');
    await assentar(f);
    expect(f.nativeElement.textContent).not.toContain('SEGREDO-MOSTRADO-UMA-VEZ');
  });
});

describe('TermoAdmin', () => {
  const t = (id: string, versao: string, status: Termo['status'], texto = 'Texto do termo. '.repeat(10)): Termo =>
    ({ id, versao, status, texto, hash: 'h'.repeat(64), criadoPor: 'admin', criadoEm: '2026-10-30T10:00:00Z', publicadoEm: status === 'RASCUNHO' ? null : '2026-10-30T10:00:00Z' });

  it('nova versão nasce rascunho a partir da vigente, edita e publica; publicada fica só para leitura', async () => {
    const vigente = t('t2', 'v2', 'VIGENTE');
    const rascunho = t('t3', 'v3', 'RASCUNHO', vigente.texto);
    const api = {
      termos: vi.fn(() => of([vigente])),
      criarTermo: vi.fn(() => of(rascunho)),
      editarTermo: vi.fn(() => of({ ...rascunho, texto: 'Novo texto do termo. '.repeat(10) })),
      publicarTermo: vi.fn(() => of({ ...rascunho, status: 'VIGENTE' as const })),
    };
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: api }] });
    const f = TestBed.createComponent(TermoAdmin);
    await assentar(f);

    clicar(f, 'Nova versão');
    await assentar(f);
    expect(api.criarTermo).toHaveBeenCalledWith(vigente.texto);
    expect(f.nativeElement.textContent).toContain('Versão v3 — RASCUNHO');
    expect((f.nativeElement.querySelector('#texto') as HTMLTextAreaElement).readOnly).toBe(false);

    clicar(f, 'Publicar como vigente');
    await assentar(f);
    expect(api.publicarTermo).toHaveBeenCalledWith('t3');
    expect(f.nativeElement.textContent).toContain('Versão v3 — VIGENTE');
    expect((f.nativeElement.querySelector('#texto') as HTMLTextAreaElement).readOnly).toBe(true); // publicada: imutável
    expect(f.nativeElement.textContent).not.toContain('Salvar rascunho');
  });

  it('mostra o motivo quando o texto é recusado', async () => {
    const rascunho = t('t3', 'v3', 'RASCUNHO');
    const api = { termos: vi.fn(() => of([rascunho])), editarTermo: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 422 }))) };
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: api }] });
    const f = TestBed.createComponent(TermoAdmin);
    await assentar(f);
    clicar(f, 'Editar');
    await assentar(f);
    clicar(f, 'Salvar rascunho');
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('Texto recusado');
  });
});
