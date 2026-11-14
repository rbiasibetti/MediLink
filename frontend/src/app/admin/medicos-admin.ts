import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminApi, MedicoAdm } from '../core/admin-api';

/** Cadastro de médicos (T20): senha inicial, ativação e a decisão de exigir o TOTP no login de cada um. */
@Component({
  selector: 'app-medicos-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <h2>Médicos</h2>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    <form class="card" (ngSubmit)="criar()">
      <h3>Novo médico</h3>
      <label for="mnome">Nome</label>
      <input id="mnome" name="mnome" [(ngModel)]="nome" required />
      <label for="mid">Registro profissional (ex.: CRM-SP 123456)</label>
      <input id="mid" name="mid" [(ngModel)]="registro" required />
      <label for="memail">E-mail (é o usuário do login)</label>
      <input id="memail" name="memail" type="email" [(ngModel)]="email" required />
      <label for="msenha">Senha inicial (12 a 128 caracteres)</label>
      <input id="msenha" name="msenha" type="password" autocomplete="new-password" [(ngModel)]="senha" required />
      <label><input type="checkbox" name="mtotp" [(ngModel)]="exigirTotp" /> Exigir TOTP (autenticador) no login</label>
      <button class="btn" type="submit" [disabled]="!nome() || !registro() || !email() || senha().length < 12">Cadastrar</button>
    </form>
    <div class="table-responsive"><table class="table table-hover table-striped align-middle">
      <thead><tr><th>Nome</th><th>E-mail</th><th>Situação</th><th>TOTP</th><th></th></tr></thead>
      <tbody>
        @for (m of lista(); track m.id) {
          <tr>
            <td>{{ m.nome }}<br /><span class="muted">{{ m.identificadorProfissional }}</span></td>
            <td>{{ m.deIntegracao ? 'integração' : m.email }}</td>
            <td>{{ m.ativo ? 'ativo' : 'desativado' }}{{ m.temSenha ? '' : ' · sem senha' }}</td>
            <td>{{ m.totpHabilitado ? (m.totpConfirmado ? 'ativo' : 'aguardando cadastro') : 'não exigido' }}</td>
            <td class="acoes">
              <button class="btn secundario" type="button" (click)="alternarAtivo(m)">{{ m.ativo ? 'Desativar' : 'Ativar' }}</button>
              <button class="btn secundario" type="button" (click)="alternarTotp(m)">{{ m.totpHabilitado ? 'Desabilitar TOTP' : 'Habilitar TOTP' }}</button>
              @if (m.totpHabilitado) { <button class="btn secundario" type="button" (click)="alternarTotp(m, true)">Refazer cadastro do TOTP</button> }
              <button class="btn secundario" type="button" (click)="abrirSenha(m)">Redefinir senha</button>
            </td>
          </tr>
        }
      </tbody>
    </table></div>
    @if (alvoSenha(); as m) {
      <form class="card" (ngSubmit)="redefinir()">
        <h3>Nova senha de {{ m.nome }}</h3>
        <label for="nsenha">Senha (12 a 128 caracteres)</label>
        <input id="nsenha" name="nsenha" type="password" autocomplete="new-password" [(ngModel)]="novaSenha" required />
        <div class="acoes">
          <button class="btn" type="submit" [disabled]="novaSenha().length < 12">Salvar</button>
          <button class="btn secundario" type="button" (click)="alvoSenha.set(null)">Cancelar</button>
        </div>
      </form>
    }
  `,
})
export class MedicosAdmin {
  private readonly api = inject(AdminApi);
  protected readonly lista = signal<MedicoAdm[]>([]);
  protected readonly erro = signal<string | null>(null);
  protected readonly nome = signal('');
  protected readonly registro = signal('');
  protected readonly email = signal('');
  protected readonly senha = signal('');
  protected readonly exigirTotp = signal(false);
  protected readonly alvoSenha = signal<MedicoAdm | null>(null);
  protected readonly novaSenha = signal('');

  constructor() {
    this.recarregar();
  }

  private recarregar(): void {
    this.api.medicos().subscribe({ next: (l) => this.lista.set(l), error: () => this.erro.set('Não foi possível carregar os médicos.') });
  }

  private falha(mensagem: string) {
    return () => this.erro.set(mensagem);
  }

  protected criar(): void {
    this.erro.set(null);
    this.api.criarMedico({ nome: this.nome(), identificadorProfissional: this.registro(), email: this.email(), senha: this.senha(), exigirTotp: this.exigirTotp() }).subscribe({
      next: () => { this.nome.set(''); this.registro.set(''); this.email.set(''); this.senha.set(''); this.exigirTotp.set(false); this.recarregar(); },
      error: this.falha('Não foi possível cadastrar (e-mail já existe ou senha fora de 12 a 128 caracteres).'),
    });
  }

  protected alternarAtivo(m: MedicoAdm): void {
    this.api.ativarMedico(m.id, !m.ativo).subscribe({ next: () => this.recarregar(), error: this.falha('Não foi possível alterar.') });
  }

  /** `refazer`: liga de novo com o TOTP já ligado, o que zera o segredo (troca de celular). */
  protected alternarTotp(m: MedicoAdm, refazer = false): void {
    this.api.totpMedico(m.id, refazer ? true : !m.totpHabilitado).subscribe({ next: () => this.recarregar(), error: this.falha('Não foi possível alterar o TOTP.') });
  }

  protected abrirSenha(m: MedicoAdm): void {
    this.novaSenha.set('');
    this.alvoSenha.set(m);
  }

  protected redefinir(): void {
    const m = this.alvoSenha();
    if (!m) return;
    this.api.redefinirSenhaMedico(m.id, this.novaSenha()).subscribe({
      next: () => { this.alvoSenha.set(null); this.novaSenha.set(''); this.recarregar(); },
      error: this.falha('Não foi possível redefinir a senha (12 a 128 caracteres).'),
    });
  }
}
