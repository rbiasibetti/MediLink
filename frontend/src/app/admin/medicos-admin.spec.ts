import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AdminApi, MedicoAdm } from '../core/admin-api';
import { MedicosAdmin } from './medicos-admin';

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
  const b = Array.from(f.nativeElement.querySelectorAll('button')).find((x) => (x.getAttribute('aria-label') ?? x.textContent ?? '').includes(rotulo));
  if (!b) throw new Error('botão não encontrado: ' + rotulo);
  b.click();
}
const medico = (o: Partial<MedicoAdm> = {}): MedicoAdm => ({
  id: 'm1', nome: 'Dr. João', identificadorProfissional: 'CRM-RS 4321', email: 'joao@clinica.test', ativo: true, temSenha: true,
  totpHabilitado: false, totpConfirmado: false, deIntegracao: false, ...o });

describe('MedicosAdmin', () => {
  function montar(api: Record<string, unknown>) {
    TestBed.configureTestingModule({ providers: [{ provide: AdminApi, useValue: { medicos: vi.fn(() => of([medico()])), ...api } }] });
    return TestBed.createComponent(MedicosAdmin);
  }

  it('cadastra o médico com a opção de TOTP escolhida pelo administrador', async () => {
    const criarMedico = vi.fn(() => of(medico()));
    const f = montar({ criarMedico });
    await assentar(f);
    digitar(f, '#mnome', 'Dr. João');
    digitar(f, '#mid', 'CRM-RS 4321');
    digitar(f, '#memail', 'joao@clinica.test');
    digitar(f, '#msenha', 'senha-do-medico-123');
    (f.nativeElement.querySelector('input[name=mtotp]') as HTMLInputElement).click();
    await assentar(f);
    clicar(f, 'Cadastrar');
    await assentar(f);

    expect(criarMedico).toHaveBeenCalledWith({ nome: 'Dr. João', identificadorProfissional: 'CRM-RS 4321', email: 'joao@clinica.test', senha: 'senha-do-medico-123', exigirTotp: true });
  });

  it('não deixa cadastrar com senha curta', async () => {
    const f = montar({});
    await assentar(f);
    digitar(f, '#mnome', 'Dr. João');
    digitar(f, '#mid', 'CRM');
    digitar(f, '#memail', 'j@clinica.test');
    digitar(f, '#msenha', 'curta');
    await assentar(f);

    const b = Array.from((f.nativeElement as HTMLElement).querySelectorAll('button')).find((x) => x.textContent?.includes('Cadastrar')) as HTMLButtonElement;
    expect(b.disabled).toBe(true);
  });

  it('habilita o TOTP, desativa e redefine a senha de um médico', async () => {
    const totpMedico = vi.fn(() => of(medico({ totpHabilitado: true })));
    const ativarMedico = vi.fn(() => of(medico({ ativo: false })));
    const redefinirSenhaMedico = vi.fn(() => of(medico()));
    const f = montar({ totpMedico, ativarMedico, redefinirSenhaMedico });
    await assentar(f);

    clicar(f, 'Habilitar TOTP');
    expect(totpMedico).toHaveBeenCalledWith('m1', true);
    clicar(f, 'Desativar');
    expect(ativarMedico).toHaveBeenCalledWith('m1', false);
    clicar(f, 'Redefinir senha');
    await assentar(f);
    digitar(f, '#nsenha', 'outra-senha-bem-longa');
    await assentar(f);
    clicar(f, 'Salvar');
    expect(redefinirSenhaMedico).toHaveBeenCalledWith('m1', 'outra-senha-bem-longa');
  });
});
