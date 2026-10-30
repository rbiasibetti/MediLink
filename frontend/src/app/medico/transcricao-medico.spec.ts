import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Api } from '../core/api';
import { TranscricaoMedico } from './transcricao-medico';

const pronta = new HttpResponse({ status: 200, body: {
  consultaId: 'c1', idioma: 'pt', duracaoMs: 65_000, criadaEm: 'x', texto: '',
  segmentos: [{ papel: 'MEDICO', inicioMs: 3_000, fimMs: 5_000, texto: 'Bom dia.' }, { papel: 'PACIENTE', inicioMs: 62_000, fimMs: 63_000, texto: 'Bom dia, doutora.' }] } });

function montar(api: Record<string, unknown>) {
  TestBed.configureTestingModule({ providers: [{ provide: Api, useValue: api }, { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ consultaId: 'c1' }) } } }] });
  return TestBed.createComponent(TranscricaoMedico);
}
async function assentar(f: { detectChanges(): void; whenStable(): Promise<unknown> }) {
  f.detectChanges();
  await f.whenStable();
  f.detectChanges();
}
const erro = (status: number, detail = '') => throwError(() => new HttpErrorResponse({ status, error: { detail } }));

describe('TranscricaoMedico', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('mostra as falas com o papel e o instante de cada uma', async () => {
    const f = montar({ transcricao: vi.fn(() => of(pronta)) });
    await assentar(f);

    const texto = f.nativeElement.textContent as string;
    expect(texto).toContain('Médico');
    expect(texto).toContain('(0:03)');
    expect(texto).toContain('Bom dia, doutora.');
    expect(texto).toContain('1:05');
  });

  it('enquanto o worker processa (202) consulta de novo até a transcrição ficar pronta', async () => {
    const api = { transcricao: vi.fn().mockReturnValueOnce(of(new HttpResponse({ status: 202, body: { status: 'EM_PROCESSAMENTO' } }))).mockReturnValue(of(pronta)) };
    const f = montar(api);
    await assentar(f);
    expect(f.nativeElement.textContent).toContain('ainda está sendo gerada');

    vi.advanceTimersByTime(5000);
    await assentar(f);

    expect(api.transcricao).toHaveBeenCalledTimes(2);
    expect(f.nativeElement.textContent).toContain('Bom dia.');
  });

  it('cada situação de erro tem a sua mensagem', async () => {
    const casos: [number, string, string][] = [
      [410, '', 'não está mais disponível'],
      [404, 'A consulta não teve áudio capturado: não há transcrição.', 'não teve áudio capturado'],
      [409, 'A transcrição falhou; o áudio foi mantido.', 'falhou'],
      [409, 'A consulta ainda não foi encerrada.', 'ainda não foi encerrada'],
      [404, '', 'não tem acesso'],
    ];
    for (const [status, detail, esperado] of casos) {
      TestBed.resetTestingModule();
      const f = montar({ transcricao: vi.fn(() => erro(status, detail)) });
      await assentar(f);
      expect(f.nativeElement.textContent).toContain(esperado);
    }
  });
});
