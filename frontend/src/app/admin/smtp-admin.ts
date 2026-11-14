import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminApi, SmtpConfig } from '../core/admin-api';

/** Configuração do servidor SMTP de envio do link ao paciente (antes só por variáveis de ambiente). */
@Component({
  selector: 'app-smtp-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe],
  template: `
    <h2>Envio de e-mail (SMTP)</h2>
    <p class="muted">Servidor usado para enviar ao paciente o link da consulta. A senha fica cifrada e nunca é exibida de volta.</p>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    @if (ok()) { <p class="text-success" role="status">{{ ok() }}</p> }

    @if (cfg(); as c) {
      <p class="muted">
        @if (c.configurado) { Configurado por {{ c.atualizadoPor }} em {{ c.atualizadoEm | date: 'dd/MM/yyyy HH:mm' }}. }
        @else { <strong>Ainda não configurado aqui.</strong> Em vigor estão os valores das variáveis de ambiente (mostrados abaixo). }
      </p>

      <form class="painel-card" (ngSubmit)="salvar()">
        <h3>Parâmetros do servidor</h3>
        <div class="grade-form">
          <div>
            <label for="host">Servidor (host)</label>
            <input id="host" name="host" class="form-control" [(ngModel)]="host" required placeholder="smtp.seu-provedor.com" />
          </div>
          <div>
            <label for="porta">Porta</label>
            <input id="porta" name="porta" class="form-control" type="number" min="1" max="65535" [(ngModel)]="porta" required />
          </div>
          <div>
            <label for="usuario">Usuário</label>
            <input id="usuario" name="usuario" class="form-control" [(ngModel)]="usuario" autocomplete="off" placeholder="(opcional)" />
          </div>
          <div>
            <label for="senha">Senha</label>
            <input id="senha" name="senha" class="form-control" type="password" autocomplete="new-password" [(ngModel)]="senha"
                   [placeholder]="c.temSenha ? '•••••••• (manter a atual)' : '(opcional)'" />
          </div>
          <div>
            <label for="remetente">Remetente (e-mail "de")</label>
            <input id="remetente" name="remetente" class="form-control" type="email" [(ngModel)]="remetente" required placeholder="no-reply@suaclinica.com.br" />
          </div>
        </div>
        <label class="linha"><input type="checkbox" name="autenticar" [(ngModel)]="autenticar" /> Exigir autenticação (usuário e senha)</label>
        <label class="linha"><input type="checkbox" name="starttls" [(ngModel)]="starttls" /> Usar STARTTLS (TLS antes de enviar a senha)</label>
        <p class="muted">Em produção, com um relay real, mantenha a autenticação e o STARTTLS ligados.</p>
        <button class="btn" type="submit" [disabled]="salvando() || !host() || !remetente()"><i class="bi bi-save" aria-hidden="true"></i> Salvar configuração</button>
      </form>

      <form class="painel-card" (ngSubmit)="testar()">
        <h3>Testar o envio</h3>
        <p class="muted">Envia um e-mail de teste com a configuração <strong>em vigor</strong> (salve antes de testar uma nova).</p>
        <label for="teste">Enviar para</label>
        <input id="teste" name="teste" class="form-control" type="email" [(ngModel)]="paraTeste" placeholder="voce@exemplo.com" />
        <button class="btn secundario" type="submit" [disabled]="testando() || !paraTeste()"><i class="bi bi-send" aria-hidden="true"></i> Enviar e-mail de teste</button>
      </form>
    } @else if (!erro()) { <p role="status">Carregando…</p> }
  `,
})
export class SmtpAdmin {
  private readonly api = inject(AdminApi);
  protected readonly cfg = signal<SmtpConfig | null>(null);
  protected readonly erro = signal<string | null>(null);
  protected readonly ok = signal<string | null>(null);
  protected readonly salvando = signal(false);
  protected readonly testando = signal(false);

  protected readonly host = signal('');
  protected readonly porta = signal(587);
  protected readonly usuario = signal('');
  protected readonly senha = signal('');
  protected readonly autenticar = signal(true);
  protected readonly starttls = signal(true);
  protected readonly remetente = signal('');
  protected readonly paraTeste = signal('');

  constructor() {
    this.carregar();
  }

  private carregar(): void {
    this.api.smtp().subscribe({
      next: (c) => { this.cfg.set(c); this.preencher(c); },
      error: () => this.erro.set('Não foi possível carregar a configuração.'),
    });
  }

  private preencher(c: SmtpConfig): void {
    this.host.set(c.host);
    this.porta.set(c.porta);
    this.usuario.set(c.usuario ?? '');
    this.autenticar.set(c.autenticar);
    this.starttls.set(c.starttls);
    this.remetente.set(c.remetente);
    this.senha.set('');
  }

  protected salvar(): void {
    this.salvando.set(true);
    this.erro.set(null);
    this.ok.set(null);
    this.api.salvarSmtp({
      host: this.host().trim(), porta: Number(this.porta()), usuario: this.usuario().trim(),
      senha: this.senha() ? this.senha() : undefined, autenticar: this.autenticar(), starttls: this.starttls(), remetente: this.remetente().trim(),
    }).subscribe({
      next: (c) => { this.salvando.set(false); this.cfg.set(c); this.preencher(c); this.ok.set('Configuração salva.'); },
      error: (e) => { this.salvando.set(false); this.erro.set(e?.status === 422 ? 'Confira o servidor, a porta e o remetente.' : 'Não foi possível salvar.'); },
    });
  }

  protected testar(): void {
    this.testando.set(true);
    this.erro.set(null);
    this.ok.set(null);
    this.api.testarSmtp(this.paraTeste().trim()).subscribe({
      next: () => { this.testando.set(false); this.ok.set('E-mail de teste enviado. Confira a caixa de entrada.'); },
      error: (e) => { this.testando.set(false); this.erro.set(e?.status === 502 ? 'O servidor recusou o envio. Revise os parâmetros.' : 'Não foi possível enviar o teste.'); },
    });
  }
}
