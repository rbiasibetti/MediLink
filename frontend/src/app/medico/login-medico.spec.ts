import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { Api } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';
import { LoginMedico } from './login-medico';

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
function entrar(f: { nativeElement: HTMLElement }) {
  (f.nativeElement.querySelector('button[type=submit]') as HTMLButtonElement).click();
}
const erro = (status: number) => throwError(() => new HttpErrorResponse({ status }));

describe('LoginMedico', () => {
  function montar(api: Record<string, unknown>) {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Api, useValue: api }] });
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    return { f: TestBed.createComponent(LoginMedico), navegar, auth: TestBed.inject(MedicoAuth) };
  }

  it('entra com e-mail e senha, guarda a sessão e vai para as consultas', async () => {
    const api = { loginMedico: vi.fn(() => of({ etapa: 'OK', token: 'JWT-MEDICO', nome: 'Dra. Ana' })) };
    const { f, navegar, auth } = montar(api);
    await assentar(f);
    digitar(f, '#usuario', 'ana@clinica.test');
    digitar(f, '#senha', 'senha-do-medico-123');
    await assentar(f);
    entrar(f);
    await assentar(f);

    expect(api.loginMedico).toHaveBeenCalledWith('ana@clinica.test', 'senha-do-medico-123', undefined);
    expect(auth.headers()).toEqual({ Authorization: 'Bearer JWT-MEDICO' });
    expect(navegar).toHaveBeenCalledWith(['/medico/consultas']);
  });

  it('com TOTP exigido mostra o segredo, pede o código e só então entra', async () => {
    const api = { loginMedico: vi.fn()
      .mockReturnValueOnce(of({ etapa: 'ENROLAR_TOTP', segredoTotp: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/x' }))
      .mockReturnValueOnce(of({ etapa: 'OK', token: 'JWT-MEDICO', nome: 'Dra. Ana' })) };
    const { f, navegar } = montar(api);
    await assentar(f);
    digitar(f, '#usuario', 'ana@clinica.test');
    digitar(f, '#senha', 'senha-do-medico-123');
    await assentar(f);
    entrar(f);
    await assentar(f);

    expect(f.nativeElement.textContent).toContain('JBSWY3DPEHPK3PXP');
    expect(navegar).not.toHaveBeenCalled();
    digitar(f, '#codigo', '123456');
    await assentar(f);
    entrar(f);
    await assentar(f);

    expect(api.loginMedico).toHaveBeenLastCalledWith('ana@clinica.test', 'senha-do-medico-123', '123456');
    expect(navegar).toHaveBeenCalledWith(['/medico/consultas']);
  });

  it('falha mostra mensagem genérica e libera o campo do código; bloqueio tem mensagem própria', async () => {
    const api = { loginMedico: vi.fn().mockReturnValueOnce(erro(403)).mockReturnValueOnce(erro(429)) };
    const { f, auth } = montar(api);
    await assentar(f);
    digitar(f, '#usuario', 'ana@clinica.test');
    digitar(f, '#senha', 'errada');
    await assentar(f);
    entrar(f);
    await assentar(f);

    expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('Não foi possível entrar');
    expect(f.nativeElement.querySelector('#codigo')).not.toBeNull();
    expect(auth.logado()).toBe(false);

    entrar(f);
    await assentar(f);
    expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('Muitas tentativas');
  });
});
