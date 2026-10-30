import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Api, SessaoLink } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';
import { AcessoMedico } from './acesso-medico';

const link = (modo: 'SALA' | 'LEITURA'): SessaoLink => ({ sessao: 'SESSAO-JWT', expiraEm: 'x', agendamentoId: 'ag1', consultaId: 'c1', modo, medicoNome: 'Dra. Ana' });

function abrir(hash: string, api: Record<string, unknown>) {
  history.replaceState(null, '', '/medico/acesso' + hash);
  TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Api, useValue: api }] });
  const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const f = TestBed.createComponent(AcessoMedico);
  f.detectChanges();
  return { f, navegar };
}

describe('AcessoMedico', () => {
  beforeEach(() => sessionStorage.clear());

  it('troca o token do fragmento por uma sessão, apaga o token da barra de endereço e vai para a sala', () => {
    const api = { trocarLink: vi.fn(() => of(link('SALA'))) };
    const { navegar } = abrir('#t=TOKEN-DE-USO-UNICO', api);

    expect(api.trocarLink).toHaveBeenCalledWith('TOKEN-DE-USO-UNICO');
    expect(window.location.hash).toBe('');                 // o token não fica na URL nem no histórico
    expect(window.location.href).not.toContain('TOKEN');
    expect(TestBed.inject(MedicoAuth).headers()).toEqual({ Authorization: 'Bearer SESSAO-JWT' });
    expect(navegar).toHaveBeenCalledWith(['/medico/consulta', 'ag1']);
  });

  it('consulta já passada abre só a leitura da transcrição', () => {
    const { navegar } = abrir('#t=OUTRO-TOKEN', { trocarLink: vi.fn(() => of(link('LEITURA'))) });

    expect(navegar).toHaveBeenCalledWith(['/medico/transcricao', 'c1']);
  });

  it('link vencido ou usado mostra a mensagem e não abre sessão', () => {
    const { f, navegar } = abrir('#t=VENCIDO', { trocarLink: vi.fn(() => throwError(() => new HttpErrorResponse({ status: 403 }))) });
    f.detectChanges();

    expect(f.nativeElement.textContent).toContain('Link inválido ou expirado');
    expect(TestBed.inject(MedicoAuth).logado()).toBe(false);
    expect(navegar).not.toHaveBeenCalled();
  });

  it('sem token no endereço nem chega a chamar a API', () => {
    const api = { trocarLink: vi.fn() };
    const { f } = abrir('', api);
    f.detectChanges();

    expect(api.trocarLink).not.toHaveBeenCalled();
    expect(f.nativeElement.textContent).toContain('Link inválido ou expirado');
  });
});
