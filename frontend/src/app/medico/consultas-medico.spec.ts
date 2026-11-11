import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Api, ConsultaResumo, PaginaConsultas } from '../core/api';
import { ConsultasMedico } from './consultas-medico';

async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}
const c = (id: string, status: string, transcricao: ConsultaResumo['transcricao']): ConsultaResumo => ({
  consultaId: id, agendamentoId: 'a-' + id, pacienteNome: 'Maria ' + id, dataHora: '2026-11-05T17:30:00Z', status, inicioEm: null, fimEm: null, transcricao });
const pagina = (itens: ConsultaResumo[], total = itens.length, n = 0, leitura = true): PaginaConsultas => ({ itens, pagina: n, tamanho: 20, total, leituraDeTranscricao: leitura });

describe('ConsultasMedico', () => {
  function montar(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Api, useValue: api }] });
    return TestBed.createComponent(ConsultasMedico);
  }

  it('lista as consultas com o acesso certo para cada situação', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('1', 'ENCERRADA', 'DISPONIVEL'), c('2', 'ENCERRADA', 'EM_PROCESSAMENTO'), c('3', 'CRIADA', 'NAO_APLICAVEL'), c('4', 'ENCERRADA', 'SEM_AUDIO')]))) });
    await assentar(f);

    const links = Array.from((f.nativeElement as HTMLElement).querySelectorAll('a')).map((a) => `${a.textContent?.trim()}|${a.getAttribute('href')}`);
    expect(links).toContain('Ver transcrição|/medico/transcricao/1');
    expect(links).toContain('Ver transcrição|/medico/transcricao/2');
    expect(links).toContain('Entrar na sala|/medico/consulta/a-3');
    expect(links.filter((l) => l.endsWith('/medico/transcricao/4'))).toHaveLength(0); // sem áudio: não há o que ler
    const texto = f.nativeElement.textContent as string;
    expect(texto).toContain('Maria 1');
    expect(texto).toContain('Sem áudio');
    expect(texto).toContain('Em processamento');
  });

  it('com a leitura desabilitada no servidor não oferece o link da transcrição e avisa', async () => {
    const f = montar({ consultas: vi.fn(() => of(pagina([c('1', 'ENCERRADA', 'DISPONIVEL')], 1, 0, false))) });
    await assentar(f);

    const links = Array.from((f.nativeElement as HTMLElement).querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(links).not.toContain('/medico/transcricao/1');
    expect(f.nativeElement.textContent).toContain('não está habilitada');
  });

  it('o filtro volta para a primeira página e a paginação navega', async () => {
    const api = { consultas: vi.fn((n: number) => of(pagina([c('x', 'ENCERRADA', 'DISPONIVEL')], 45, n))) };
    const f = montar(api);
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('Página 1 de 3');

    (Array.from((f.nativeElement as HTMLElement).querySelectorAll('button')).find((b) => b.textContent?.includes('Próxima')) as HTMLButtonElement).click();
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
