import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Api, AgendamentoStatus, ConsultaResumo, PaginaConsultas } from '../core/api';
import { ConsultasMedico } from './consultas-medico';

async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}
const c = (id: string, status: string, transcricao: ConsultaResumo['transcricao'], ag: AgendamentoStatus = 'AGENDADO'): ConsultaResumo => ({
  consultaId: id, agendamentoId: 'a-' + id, pacienteNome: 'Maria ' + id, dataHora: '2026-11-05T17:30:00Z',
  status, agendamentoStatus: ag, roomName: 'sala-' + id, inicioEm: null, fimEm: null, transcricao });
const pagina = (itens: ConsultaResumo[], total = itens.length, n = 0, leitura = true): PaginaConsultas => ({ itens, pagina: n, tamanho: 20, total, leituraDeTranscricao: leitura });

function acoes(f: { nativeElement: HTMLElement }, seletor = 'a,button') {
  return Array.from(f.nativeElement.querySelectorAll(seletor)).map((e) => `${e.getAttribute('aria-label') ?? e.textContent?.trim()}|${e.getAttribute('href') ?? ''}`);
}
function clicarRotulo(f: { nativeElement: HTMLElement }, rotulo: string) {
  const el = Array.from(f.nativeElement.querySelectorAll('a,button')).find((e) => (e.getAttribute('aria-label') ?? e.textContent ?? '').includes(rotulo));
  if (!el) throw new Error('controle não encontrado: ' + rotulo);
  (el as HTMLElement).click();
}

describe('ConsultasMedico', () => {
  function montar(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Api, useValue: api }] });
    return TestBed.createComponent(ConsultasMedico);
  }

  it('oferece os ícones de ação certos para cada situação', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('1', 'ENCERRADA', 'DISPONIVEL'), c('2', 'ENCERRADA', 'EM_PROCESSAMENTO'), c('3', 'CRIADA', 'NAO_APLICAVEL'), c('4', 'ENCERRADA', 'SEM_AUDIO')]))) });
    await assentar(f);

    const itens = acoes(f);
    expect(itens).toContain('Ver a transcrição|/medico/transcricao/1');
    expect(itens).toContain('Ver a transcrição|/medico/transcricao/2');
    expect(itens).toContain('Entrar na sala|/medico/consulta/a-3');
    expect(itens).toContain('Rever o link do paciente|');       // consulta agendada tem o ícone de link
    expect(itens).toContain('Cancelar consulta|');              // e o de cancelar
    expect(itens.filter((l) => l.endsWith('/medico/transcricao/4'))).toHaveLength(0); // sem áudio: não há o que ler
    const texto = f.nativeElement.textContent as string;
    expect(texto).toContain('Maria 1');
    expect(texto).toContain('Sem áudio');
  });

  it('consulta cancelada aparece como "Cancelada" e sem ações', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('9', 'CRIADA', 'NAO_APLICAVEL', 'CANCELADO')]))) });
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('Cancelada');
    expect(acoes(f)).not.toContain('Cancelar consulta|');
    expect(acoes(f)).not.toContain('Entrar na sala|/medico/consulta/a-9');
  });

  it('com a leitura desabilitada no servidor não oferece o ícone da transcrição e avisa', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('1', 'ENCERRADA', 'DISPONIVEL')], 1, 0, false))) });
    await assentar(f);

    expect(acoes(f, 'a').map((l) => l.split('|')[1])).not.toContain('/medico/transcricao/1');
    expect(f.nativeElement.textContent).toContain('não está habilitada');
  });

  it('rever o link mostra o endereço do paciente para copiar', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('3', 'CRIADA', 'NAO_APLICAVEL')]))) });
    await assentar(f);

    clicarRotulo(f, 'Rever o link do paciente');
    await assentar(f);
    const campo = f.nativeElement.querySelector('.modal-caixa input') as HTMLInputElement;
    expect(campo.value).toContain('/sala/sala-3');
  });

  it('cancela a consulta após confirmação e recarrega a lista', async () => {
    const cancelarConsulta = vi.fn(() => of(void 0));
    const consultas = vi.fn(() => of(pagina([c('3', 'CRIADA', 'NAO_APLICAVEL')])));
    const f = montar({ consultas, cancelarConsulta });
    await assentar(f);

    clicarRotulo(f, 'Cancelar consulta');            // abre o diálogo
    await assentar(f);
    expect(f.nativeElement.querySelector('.modal-caixa')?.textContent).toContain('Cancelar a consulta');
    clicarRotulo(f, 'Cancelar a consulta');          // confirma
    await assentar(f);

    expect(cancelarConsulta).toHaveBeenCalledWith('3');
    expect(consultas).toHaveBeenCalledTimes(2);       // recarregou
    expect(f.nativeElement.textContent).toContain('cancelada');
  });

  it('cancelamento recusado (409) mostra o motivo no diálogo', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('3', 'CRIADA', 'NAO_APLICAVEL')]))), cancelarConsulta: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 409 }))) });
    await assentar(f);
    clicarRotulo(f, 'Cancelar consulta');
    await assentar(f);
    clicarRotulo(f, 'Cancelar a consulta');
    await assentar(f);
    expect(f.nativeElement.querySelector('.modal-caixa [role=alert]')?.textContent).toContain('já começou');
  });

  it('o filtro volta para a primeira página e a paginação navega', async () => {
    const api = { consultas: vi.fn((n: number) => of(pagina([c('x', 'ENCERRADA', 'DISPONIVEL')], 45, n))) };
    const f = montar(api);
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Página 1 de 3');

    clicarRotulo(f, 'Próxima');
    await assentar(f);
    expect(api.consultas).toHaveBeenLastCalledWith(1, undefined);

    const filtro = f.nativeElement.querySelector('#filtro') as HTMLSelectElement;
    filtro.value = 'ENCERRADA';
    filtro.dispatchEvent(new Event('change'));
    await assentar(f);
    expect(api.consultas).toHaveBeenLastCalledWith(0, 'ENCERRADA');
  });

  it('sessão vencida (401) volta ao login', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Api, useValue: { consultas: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 401 }))) } }] });
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const f = TestBed.createComponent(ConsultasMedico);
    await assentar(f);
    expect(navegar).toHaveBeenCalledWith(['/medico']);
  });

  it('erro de servidor mostra aviso sem sair', async () => {
    const f = montar({ consultas: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 500 }))) });
    await assentar(f);
    expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('Não foi possível carregar');
  });
});
