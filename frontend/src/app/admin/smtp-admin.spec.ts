import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AdminApi, SmtpConfig } from '../core/admin-api';
import { SmtpAdmin } from './smtp-admin';

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
const cfg = (o: Partial<SmtpConfig> = {}): SmtpConfig => ({
  host: 'smtp.exemplo.com', porta: 587, usuario: 'u', temSenha: false, autenticar: true, starttls: true,
  remetente: 'no-reply@exemplo.com', configurado: false, atualizadoPor: null, atualizadoEm: null, ...o });

describe('SmtpAdmin', () => {
  function montar(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: { smtp: vi.fn(() => of(cfg())), ...api } }] });
    return TestBed.createComponent(SmtpAdmin);
  }

  it('carrega a configuração e preenche o formulário', async () => {
    const f = montar({ smtp: vi.fn(() => of(cfg({ host: 'mail.clinica.com', porta: 465 }))) });
    await assentar(f);
    expect((f.nativeElement.querySelector('#host') as HTMLInputElement).value).toBe('mail.clinica.com');
    expect((f.nativeElement.querySelector('#porta') as HTMLInputElement).value).toBe('465');
  });

  it('salva sem reenviar a senha quando o campo fica em branco', async () => {
    const salvarSmtp = vi.fn(() => of(cfg({ configurado: true })));
    const f = montar({ salvarSmtp });
    await assentar(f);
    digitar(f, '#host', 'smtp.novo.com');
    digitar(f, '#remetente', 'envio@novo.com');
    await assentar(f);
    clicar(f, 'Salvar configuração');
    await assentar(f);
    expect(salvarSmtp).toHaveBeenCalledWith(expect.objectContaining({ host: 'smtp.novo.com', remetente: 'envio@novo.com', senha: undefined }));
    expect(f.nativeElement.textContent).toContain('Configuração salva');
  });

  it('envia um e-mail de teste para o endereço informado', async () => {
    const testarSmtp = vi.fn(() => of(void 0));
    const f = montar({ testarSmtp });
    await assentar(f);
    digitar(f, '#teste', 'eu@exemplo.com');
    await assentar(f);
    clicar(f, 'Enviar e-mail de teste');
    await assentar(f);
    expect(testarSmtp).toHaveBeenCalledWith('eu@exemplo.com');
    expect(f.nativeElement.textContent).toContain('teste enviado');
  });
});
