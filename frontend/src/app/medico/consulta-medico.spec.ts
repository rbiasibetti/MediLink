import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { Api, ConsultaDetalhe, Sessao } from '../core/api';
import { JitsiRoom } from '../core/jitsi-room';
import { ConsultaMedico } from './consulta-medico';

@Component({ selector: 'app-jitsi-room', template: '' })
class JitsiStub {
  readonly roomName = input.required<string>();
  readonly jwt = input.required<string>();
  readonly papel = input.required<string>();
  readonly transcrever = input(false);
  readonly pronto = output<void>();
  readonly participante = output<string>();
  readonly saiu = output<void>();
}

const sessao: Sessao = { estado: 'ADMITIDO', roomName: 'consulta-abc', consultaId: 'c1', jwt: 'JWT-MEDICO' };
const detalhe = (status: string, extra: Partial<ConsultaDetalhe> = {}): ConsultaDetalhe => ({
  id: 'c1', roomName: 'consulta-abc', status, inicioEm: null, fimEm: null, pacienteAguardando: false,
  consentimentoEm: null, consentimentoVersao: null, capturaAutorizada: false, podeReabrir: false, ...extra,
});

function montar(api: Record<string, unknown>) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: Api, useValue: api },
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ agendamentoId: 'ag1' }) } } },
    ],
  });
  TestBed.overrideComponent(ConsultaMedico, { remove: { imports: [JitsiRoom] }, add: { imports: [JitsiStub] } });
  return TestBed.createComponent(ConsultaMedico);
}
async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}

describe('ConsultaMedico', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('ao sair com a consulta em andamento, encerra no backend', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('EM_ANDAMENTO'))),
      encerrar: vi.fn(() => of(detalhe('ENCERRADA', { fimEm: '2026-11-03T15:00:00Z' }))),
    };
    const f = montar(api);
    await assentar(f);

    f.debugElement.query(By.directive(JitsiStub)).componentInstance.saiu.emit();
    await assentar(f);

    expect(api.encerrar).toHaveBeenCalledWith('c1');
    expect(f.nativeElement.textContent).toContain('Consulta encerrada');
  });

  it('ao sair sem ter admitido o paciente, NÃO encerra', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('AGUARDANDO_PACIENTE', { pacienteAguardando: true }))),
      encerrar: vi.fn(),
    };
    const f = montar(api);
    await assentar(f);

    f.debugElement.query(By.directive(JitsiStub)).componentInstance.saiu.emit();
    await assentar(f);

    expect(api.encerrar).not.toHaveBeenCalled();
    expect(f.nativeElement.textContent).toContain('A consulta ainda não começou');
  });

  it('mostra o botão de aceite só com paciente aguardando e aceita', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('AGUARDANDO_PACIENTE', { pacienteAguardando: true }))),
      aceitar: vi.fn(() => of(detalhe('EM_ANDAMENTO', { consentimentoEm: 'x', capturaAutorizada: true }))),
    };
    const f = montar(api);
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Paciente aguardando');

    const botao = () => (Array.from(f.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find((b) => b.textContent?.includes('Aceitar paciente'))!;
    // Enquanto a sala não está pronta (configuração ao entrar) o aceite fica bloqueado.
    expect(botao().disabled).toBe(true);
    expect(f.nativeElement.textContent).toContain('Preparando a sala');
    botao().click();
    expect(api.aceitar).not.toHaveBeenCalled();

    f.debugElement.query(By.directive(JitsiStub)).componentInstance.pronto.emit();
    await assentar(f);
    expect(botao().disabled).toBe(false);
    botao().click();
    await assentar(f);

    expect(api.aceitar).toHaveBeenCalledWith('c1');
    expect(f.nativeElement.textContent).toContain('Consulta em andamento');
    expect(f.nativeElement.textContent).toContain('Consulta sendo registrada');
  });

  it('consulta em andamento sem registro (consentimento retirado) avisa o médico para encerrar', async () => {
    const api = { tokenMedico: vi.fn(() => of(sessao)), detalhe: vi.fn(() => of(detalhe('EM_ANDAMENTO'))) };
    const f = montar(api);
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('esta consulta não está sendo registrada');
  });

  it('pede a transcrição ao Jitsi só enquanto a captura está autorizada (consentimento + consulta em andamento)', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('EM_ANDAMENTO'))),
    };
    const f = montar(api);
    await assentar(f);
    expect(f.debugElement.query(By.directive(JitsiStub)).componentInstance.transcrever()).toBe(false);

    api.detalhe.mockReturnValue(of(detalhe('EM_ANDAMENTO', { consentimentoEm: 'x', capturaAutorizada: true })));
    vi.advanceTimersByTime(3000);
    await assentar(f);
    expect(f.debugElement.query(By.directive(JitsiStub)).componentInstance.transcrever()).toBe(true);

    api.detalhe.mockReturnValue(of(detalhe('EM_ANDAMENTO'))); // o paciente retirou o consentimento
    vi.advanceTimersByTime(3000);
    await assentar(f);
    expect(f.debugElement.query(By.directive(JitsiStub)).componentInstance.transcrever()).toBe(false);
  });

  it('informa ao backend o endpoint do Jitsi do médico quando ele entra na sala', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('AGUARDANDO_PACIENTE'))),
      registrarParticipanteMedico: vi.fn(() => of({ papel: 'MEDICO' })),
    };
    const f = montar(api);
    await assentar(f);

    f.debugElement.query(By.directive(JitsiStub)).componentInstance.participante.emit('7bc45eb8');
    await assentar(f);

    expect(api.registrarParticipanteMedico).toHaveBeenCalledWith('c1', '7bc45eb8');
  });

  it('o médico entra na sala com o papel de médico (só ele aciona a criptografia, quando o flag está ligado)', async () => {
    const f = montar({ tokenMedico: vi.fn(() => of(sessao)), detalhe: vi.fn(() => of(detalhe('AGUARDANDO_PACIENTE'))) });
    await assentar(f);

    expect(f.debugElement.query(By.directive(JitsiStub)).componentInstance.papel()).toBe('medico');
  });

  it('encerrada há pouco: oferece reabrir, e reabrir volta à sala', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('EM_ANDAMENTO'))),
      encerrar: vi.fn(() => of(detalhe('ENCERRADA', { fimEm: '2026-11-03T15:00:00Z', podeReabrir: true }))),
      reabrir: vi.fn(() => of(detalhe('EM_ANDAMENTO'))),
    };
    const f = montar(api);
    await assentar(f);
    f.debugElement.query(By.directive(JitsiStub)).componentInstance.saiu.emit();
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Consulta encerrada');
    expect(f.nativeElement.textContent).toContain('Reabrir a consulta');

    const botao = Array.from(f.nativeElement.querySelectorAll('button')).find((b) => (b as HTMLElement).textContent?.includes('Reabrir')) as HTMLButtonElement;
    botao.click();
    await assentar(f);

    expect(api.reabrir).toHaveBeenCalledWith('c1');
    expect(f.debugElement.query(By.directive(JitsiStub))).not.toBeNull(); // de volta à sala
  });

  it('encerrada fora do prazo não oferece reabrir', async () => {
    const api = {
      tokenMedico: vi.fn(() => of(sessao)),
      detalhe: vi.fn(() => of(detalhe('EM_ANDAMENTO'))),
      encerrar: vi.fn(() => of(detalhe('ENCERRADA', { podeReabrir: false }))),
    };
    const f = montar(api);
    await assentar(f);
    f.debugElement.query(By.directive(JitsiStub)).componentInstance.saiu.emit();
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('Consulta encerrada');
    expect(f.nativeElement.textContent).not.toContain('Reabrir a consulta');
  });
});
