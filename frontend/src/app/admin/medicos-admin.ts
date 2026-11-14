import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
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
    <div class="lista-barra">
      <span class="busca">
        <i class="bi bi-search" aria-hidden="true"></i>
        <input class="form-control" type="search" [ngModel]="busca()" (ngModelChange)="buscar($event)" placeholder="Buscar por nome, e-mail ou registro…" aria-label="Buscar médico" />
      </span>
    </div>
    @if (pagina().length === 0) { <p class="muted">Nenhum médico encontrado.</p> }
    @else {
    <div class="table-responsive"><table class="table table-hover table-striped align-middle">
      <thead><tr><th>Nome</th><th>E-mail</th><th>Situação</th><th>TOTP</th><th class="text-end">Ações</th></tr></thead>
      <tbody>
        @for (m of pagina(); track m.id) {
          <tr>
            <td>{{ m.nome }}<br /><span class="muted">{{ m.identificadorProfissional }}</span></td>
            <td>{{ m.deIntegracao ? 'integração' : m.email }}</td>
            <td>{{ m.ativo ? 'ativo' : 'desativado' }}{{ m.temSenha ? '' : ' · sem senha' }}</td>
            <td>{{ m.totpHabilitado ? (m.totpConfirmado ? 'ativo' : 'aguardando cadastro') : 'não exigido' }}</td>
            <td class="text-end"><div class="acoes-tabela justify-content-end">
              <button class="btn-icone" type="button" (click)="alternarAtivo(m)" [title]="m.ativo ? 'Desativar' : 'Ativar'" [attr.aria-label]="m.ativo ? 'Desativar' : 'Ativar'"><i class="bi" [class.bi-toggle-on]="m.ativo" [class.bi-toggle-off]="!m.ativo" aria-hidden="true"></i></button>
              <button class="btn-icone" type="button" (click)="alternarTotp(m)" [title]="m.totpHabilitado ? 'Desabilitar TOTP' : 'Habilitar TOTP'" [attr.aria-label]="m.totpHabilitado ? 'Desabilitar TOTP' : 'Habilitar TOTP'"><i class="bi" [class.bi-shield-lock]="!m.totpHabilitado" [class.bi-shield-slash]="m.totpHabilitado" aria-hidden="true"></i></button>
              @if (m.totpHabilitado) { <button class="btn-icone" type="button" (click)="alternarTotp(m, true)" title="Refazer cadastro do TOTP" aria-label="Refazer cadastro do TOTP"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i></button> }
              <button class="btn-icone" type="button" (click)="abrirSenha(m)" title="Redefinir senha" aria-label="Redefinir senha"><i class="bi bi-key" aria-hidden="true"></i></button>
            </div></td>
          </tr>
        }
      </tbody>
    </table></div>
    <div class="paginacao">
      <span class="muted">{{ filtrados().length }} médico(s) · página {{ indice() + 1 }} de {{ totalPaginas() }}</span>
      <button class="btn-icone" type="button" [disabled]="indice() === 0" (click)="ir(indice() - 1)" title="Anterior" aria-label="Página anterior"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
      <button class="btn-icone" type="button" [disabled]="indice() + 1 >= totalPaginas()" (click)="ir(indice() + 1)" title="Próxima" aria-label="Próxima página"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
    </div>
    }
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
  private static readonly POR_PAGINA = 8;
  private readonly api = inject(AdminApi);
  protected readonly lista = signal<MedicoAdm[]>([]);
  protected readonly busca = signal('');
  protected readonly indice = signal(0);
  protected readonly filtrados = computed(() => {
    const q = this.busca().trim().toLowerCase();
    const l = this.lista();
    return q ? l.filter((m) => (m.nome + ' ' + m.email + ' ' + m.identificadorProfissional).toLowerCase().includes(q)) : l;
  });
  protected readonly totalPaginas = computed(() => Math.max(1, Math.ceil(this.filtrados().length / MedicosAdmin.POR_PAGINA)));
  protected readonly pagina = computed(() => this.filtrados().slice(this.indice() * MedicosAdmin.POR_PAGINA, this.indice() * MedicosAdmin.POR_PAGINA + MedicosAdmin.POR_PAGINA));
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

  protected buscar(q: string): void {
    this.busca.set(q);
    this.indice.set(0);
  }

  protected ir(n: number): void {
    this.indice.set(Math.max(0, Math.min(n, this.totalPaginas() - 1)));
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
