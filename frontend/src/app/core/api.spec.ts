import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Api } from './api';
import { MedicoAuth } from './medico-auth';

describe('Api', () => {
  let api: Api;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(Api);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('espera (202) chega como sessão sem JWT', () => {
    let sessao: unknown;
    api.tokenPaciente('consulta-abc', 'tok').subscribe((s) => (sessao = s));
    const req = http.expectOne('/api/salas/consulta-abc/token');
    expect(req.request.body).toEqual({ tokenEntrada: 'tok' });
    req.flush({ estado: 'AGUARDANDO_MEDICO', roomName: 'consulta-abc', consultaId: 'c1', jwt: null }, { status: 202, statusText: 'Accepted' });
    expect(sessao).toMatchObject({ estado: 'AGUARDANDO_MEDICO', jwt: null });
  });

  it('o CPF vai no corpo do POST, nunca na URL', () => {
    api.validarCpf('consulta-abc', '529.982.247-25').subscribe();
    const req = http.expectOne((r) => r.url.startsWith('/api/salas/consulta-abc/validar-cpf'));
    expect(req.request.url).not.toContain('529');
    expect(req.request.body).toEqual({ cpf: '529.982.247-25' });
    req.flush({ tokenEntrada: 't', expiraEm: 'x' });
  });

  it('sem validação de CPF o corpo é vazio', () => {
    api.validarCpf('consulta-abc', null).subscribe();
    const req = http.expectOne('/api/salas/consulta-abc/validar-cpf');
    expect(req.request.body).toBeNull();
    req.flush({ tokenEntrada: 't', expiraEm: 'x' });
  });

  it('chamadas de paciente não levam Authorization; as de médico levam', () => {
    TestBed.inject(MedicoAuth).definir('m@c.t', 's');
    api.acesso('consulta-abc').subscribe();
    expect(http.expectOne('/api/salas/consulta-abc/acesso').request.headers.has('Authorization')).toBe(false);

    api.tokenMedico('ag1').subscribe();
    expect(http.expectOne('/api/agendamentos/ag1/token-jitsi').request.headers.get('Authorization')).toMatch(/^Basic /);

    api.encerrar('c1').subscribe();
    expect(http.expectOne('/api/consultas/c1/encerrar').request.headers.has('Authorization')).toBe(true);
  });

  it('registrar o endpoint: médico autenticado; paciente com o token no corpo e sem Authorization', () => {
    TestBed.inject(MedicoAuth).definir('m@c.t', 's');
    api.registrarParticipanteMedico('c1', '7bc45eb8').subscribe();
    const m = http.expectOne('/api/consultas/c1/participante');
    expect(m.request.headers.has('Authorization')).toBe(true);
    expect(m.request.body).toEqual({ endpointId: '7bc45eb8' });

    api.registrarParticipantePaciente('c1', 'tok', 'd88aeac9').subscribe();
    const p = http.expectOne('/api/consultas/c1/participante');
    expect(p.request.headers.has('Authorization')).toBe(false);
    expect(p.request.body).toEqual({ endpointId: 'd88aeac9', tokenEntrada: 'tok' });
  });

  it('o consentimento é do paciente: usa o token de entrada, não o login do médico', () => {
    TestBed.inject(MedicoAuth).definir('m@c.t', 's');
    api.consentir('c1', 'tok', true, 'v1').subscribe();
    const req = http.expectOne('/api/consultas/c1/consentimento');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.body).toEqual({ tokenEntrada: 'tok', aceito: true, versaoTermo: 'v1' });
  });
});
