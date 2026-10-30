import { HttpErrorResponse } from '@angular/common/http';
import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Acesso, Api, Sessao } from '../core/api';
import { JitsiRoom } from '../core/jitsi-room';
import { AcessoPaciente } from './acesso-paciente';

@Component({ selector: 'app-jitsi-room', template: '<i class="jitsi-stub"></i>' })
class JitsiStub {
  readonly roomName = input.required<string>();
  readonly jwt = input.required<string>();
  readonly papel = input.required<string>();
  readonly transcrever = input(false);
  readonly pronto = output<void>();
  readonly participante = output<string>();
  readonly saiu = output<void>();
}

const espera: Sessao = { estado: 'AGUARDANDO_MEDICO', roomName: 'consulta-abc', consultaId: 'c1', jwt: null };
const admitido: Sessao = { estado: 'ADMITIDO', roomName: 'consulta-abc', consultaId: 'c1', jwt: 'JWT-DO-PACIENTE' };
const acesso = (cpfObrigatorio: boolean): Acesso => ({ medicoNome: 'Dra. Ana', dataHora: '2026-11-03T14:00:00Z', cpfObrigatorio });

function montar(api: Partial<Record<keyof Api, unknown>>) {
  TestBed.configureTestingModule({
    providers: [
      { provide: Api, useValue: api },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ roomName: 'consulta-abc' }) } } },
    ],
  });
  TestBed.overrideComponent(AcessoPaciente, { remove: { imports: [JitsiRoom] }, add: { imports: [JitsiStub] } });
  const fixture = TestBed.createComponent(AcessoPaciente);
  return fixture;
}
const texto = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent ?? '';
async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}
function clicar(f: { nativeElement: HTMLElement }, rotulo: string) {
  const b = Array.from(f.nativeElement.querySelectorAll('button')).find((x) => x.textContent?.includes(rotulo));
  if (!b) throw new Error('botão não encontrado: ' + rotulo);
  b.click();
}

describe('AcessoPaciente', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('pede o CPF quando o médico exige e não avança com CPF recusado', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(true))),
      validarCpf: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))),
    };
    const f = montar(api);
    await assentar(f);
    expect(f.nativeElement.querySelector('#cpf')).not.toBeNull();

    const input = f.nativeElement.querySelector('#cpf') as HTMLInputElement;
    input.value = '111.444.777-35';
    input.dispatchEvent(new Event('input'));
    await assentar(f);
    clicar(f, 'Continuar');
    await assentar(f);

    expect(api.validarCpf).toHaveBeenCalledWith('consulta-abc', '111.444.777-35');
    expect(texto(f)).toContain('Não foi possível confirmar o seu acesso');
    expect(f.debugElement.query(By.directive(JitsiStub))).toBeNull();
  });

  it('avisa quando há muitas tentativas', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(true))),
      validarCpf: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 429 }))),
    };
    const f = montar(api);
    await assentar(f);
    const input = f.nativeElement.querySelector('#cpf') as HTMLInputElement;
    input.value = '52998224725';
    input.dispatchEvent(new Event('input'));
    await assentar(f);
    clicar(f, 'Continuar');
    await assentar(f);

    expect(texto(f)).toContain('Muitas tentativas');
  });

  it('sem validação de CPF entra direto na espera e mostra o termo', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(false))),
      validarCpf: vi.fn(() => of({ tokenEntrada: 'tok', expiraEm: 'x' })),
      termo: vi.fn(() => of({ versao: 'v1', texto: 'TEXTO DO TERMO' })),
      tokenPaciente: vi.fn(() => of(espera)),
    };
    const f = montar(api);
    await assentar(f);

    expect(api.validarCpf).toHaveBeenCalledWith('consulta-abc', null);
    expect(f.nativeElement.querySelector('#cpf')).toBeNull();
    expect(texto(f)).toContain('TEXTO DO TERMO');
    expect(f.debugElement.query(By.directive(JitsiStub))).toBeNull();
  });

  it('admitido antes de responder ao termo ainda não entra na sala; entra depois da resposta', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(false))),
      validarCpf: vi.fn(() => of({ tokenEntrada: 'tok', expiraEm: 'x' })),
      termo: vi.fn(() => of({ versao: 'v1', texto: 'TERMO' })),
      tokenPaciente: vi.fn().mockReturnValueOnce(of(espera)).mockReturnValue(of(admitido)),
      consentir: vi.fn(() => of({ consentimentoVigente: true, versaoDoTermo: 'v1' })),
    };
    const f = montar(api);
    await assentar(f);
    expect(f.debugElement.query(By.directive(JitsiStub))).toBeNull();

    vi.advanceTimersByTime(3000); // próxima consulta: agora o médico já aceitou
    await assentar(f);
    expect(api.tokenPaciente).toHaveBeenCalledTimes(2);
    expect(f.debugElement.query(By.directive(JitsiStub))).toBeNull(); // falta responder ao termo

    clicar(f, 'Aceito');
    await assentar(f);
    expect(api.consentir).toHaveBeenCalledWith('c1', 'tok', true, 'v1');
    const stub = f.debugElement.query(By.directive(JitsiStub));
    expect(stub).not.toBeNull();
    expect(stub.componentInstance.jwt()).toBe('JWT-DO-PACIENTE');
    expect(stub.componentInstance.papel()).toBe('paciente');
  });

  it('informa ao backend o endpoint do Jitsi do paciente, com o token de entrada', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(false))),
      validarCpf: vi.fn(() => of({ tokenEntrada: 'tok', expiraEm: 'x' })),
      termo: vi.fn(() => of({ versao: 'v1', texto: 'TERMO' })),
      tokenPaciente: vi.fn(() => of(admitido)),
      consentir: vi.fn(() => of({ consentimentoVigente: true, versaoDoTermo: 'v1' })),
      registrarParticipantePaciente: vi.fn(() => of({ papel: 'PACIENTE' })),
    };
    const f = montar(api);
    await assentar(f);
    clicar(f, 'Aceito');
    await assentar(f);

    f.debugElement.query(By.directive(JitsiStub)).componentInstance.participante.emit('d88aeac9');
    await assentar(f);

    expect(api.registrarParticipantePaciente).toHaveBeenCalledWith('c1', 'tok', 'd88aeac9');
  });

  it('recusar o termo também libera a entrada, sem transcrição', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(false))),
      validarCpf: vi.fn(() => of({ tokenEntrada: 'tok', expiraEm: 'x' })),
      termo: vi.fn(() => of({ versao: 'v1', texto: 'TERMO' })),
      tokenPaciente: vi.fn(() => of(admitido)),
      consentir: vi.fn(() => of({ consentimentoVigente: false, versaoDoTermo: null })),
    };
    const f = montar(api);
    await assentar(f);

    clicar(f, 'Não aceito');
    await assentar(f);

    expect(api.consentir).toHaveBeenCalledWith('c1', 'tok', false, 'v1');
    expect(f.debugElement.query(By.directive(JitsiStub))).not.toBeNull();
    expect(texto(f)).toContain('Transcrição não autorizada');
  });

  it('token de entrada expirado leva à tela de sessão expirada', async () => {
    const api = {
      acesso: vi.fn(() => of(acesso(false))),
      validarCpf: vi.fn(() => of({ tokenEntrada: 'tok', expiraEm: 'x' })),
      termo: vi.fn(() => of({ versao: 'v1', texto: 'TERMO' })),
      tokenPaciente: vi.fn().mockReturnValueOnce(of(espera)).mockReturnValue(throwError(() => new HttpErrorResponse({ status: 403 }))),
    };
    const f = montar(api);
    await assentar(f);
    vi.advanceTimersByTime(3000);
    await assentar(f);

    expect(texto(f)).toContain('Sessão expirada');
  });

  it('link inválido mostra consulta não encontrada', async () => {
    const f = montar({ acesso: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 404 }))) });
    await assentar(f);
    expect(texto(f)).toContain('Consulta não encontrada');
  });
});
