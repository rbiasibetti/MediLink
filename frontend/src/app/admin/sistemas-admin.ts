import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { AdminApi, ChaveApi, Integrador } from '../core/admin-api';

const ESCOPOS = ['agendamento:escrever', 'agendamento:ler', 'sala:iniciar'];

/** Sistemas integrados, suas API keys (mostradas uma única vez) e o webhook de devolução (T16/T18/T19). */
@Component({
  selector: 'app-sistemas-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe],
  template: `
    <h2>Sistemas integrados</h2>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    @if (segredoNovo(); as s) {
      <div class="card" role="status">
        <p><strong>Guarde agora: este valor não será mostrado de novo.</strong></p>
        <p>{{ s.rotulo }}: <code>{{ s.valor }}</code></p>
        <button class="btn secundario" type="button" (click)="segredoNovo.set(null)">Já guardei</button>
      </div>
    }
    <form class="acoes" (ngSubmit)="criar()">
      <input name="nome" placeholder="Nome do novo sistema" [(ngModel)]="nome" required />
      <button class="btn" type="submit" [disabled]="!nome()">Cadastrar</button>
    </form>
    <table><thead><tr><th>Nome</th><th>Situação</th><th>Webhook</th><th></th></tr></thead>
      <tbody>@for (i of lista(); track i.id) {
        <tr><td>{{ i.nome }}</td><td>{{ i.ativo ? 'ativo' : 'desativado' }}</td>
          <td>{{ i.webhookAtivo ? i.webhookBaseUrl : '—' }}</td>
          <td><button class="btn secundario" type="button" (click)="abrir(i)">Abrir</button>
            <button class="btn secundario" type="button" (click)="alternar(i)">{{ i.ativo ? 'Desativar' : 'Ativar' }}</button></td></tr> }</tbody></table>

    @if (aberto(); as i) {
      <section class="card">
        <h3>{{ i.nome }}</h3>
        <h4>API keys</h4>
        <table><thead><tr><th>Nome</th><th>Escopos</th><th>Último uso</th><th>Situação</th><th></th></tr></thead>
          <tbody>@for (k of chaves(); track k.id) {
            <tr><td>{{ k.nome }}</td><td>{{ k.escopos }}</td><td>{{ k.ultimoUsoEm ? (k.ultimoUsoEm | date: 'dd/MM HH:mm') : 'nunca' }}</td>
              <td>{{ k.ativa ? 'ativa' : 'revogada' }}</td>
              <td>@if (k.ativa) { <button class="btn secundario" type="button" (click)="revogar(k)">Revogar</button> }</td></tr> }</tbody></table>
        <form (ngSubmit)="criarChave()">
          <input name="nomeKey" placeholder="Nome da key (ex.: produção)" [(ngModel)]="nomeKey" required />
          @for (e of escopos; track e) {
            <label><input type="checkbox" [checked]="escoposMarcados().includes(e)" (change)="marcar(e)" /> {{ e }}</label>
          }
          <button class="btn" type="submit" [disabled]="!nomeKey() || escoposMarcados().length === 0">Gerar key</button>
        </form>
        <h4>Webhook de devolução</h4>
        <form (ngSubmit)="salvarWebhook()">
          <label for="base">URL base (HTTPS)</label>
          <input id="base" name="base" [(ngModel)]="base" placeholder="https://api.cliente.com.br/hooks/medilink" />
          <label><input type="checkbox" name="wativo" [(ngModel)]="webhookAtivo" /> Ativo</label>
          <label><input type="checkbox" name="novoSegredo" [(ngModel)]="novoSegredo" /> Gerar novo segredo de assinatura</label>
          <button class="btn" type="submit">Salvar webhook</button>
        </form>
      </section>
    }
  `,
})
export class SistemasAdmin {
  private readonly api = inject(AdminApi);
  protected readonly escopos = ESCOPOS;
  protected readonly lista = signal<Integrador[]>([]);
  protected readonly aberto = signal<Integrador | null>(null);
  protected readonly chaves = signal<ChaveApi[]>([]);
  protected readonly erro = signal<string | null>(null);
  protected readonly segredoNovo = signal<{ rotulo: string; valor: string } | null>(null);
  protected readonly nome = signal('');
  protected readonly nomeKey = signal('');
  protected readonly escoposMarcados = signal<string[]>(['agendamento:escrever', 'agendamento:ler', 'sala:iniciar']);
  protected readonly base = signal('');
  protected readonly webhookAtivo = signal(false);
  protected readonly novoSegredo = signal(false);

  constructor() {
    this.recarregar();
  }

  private recarregar(): void {
    this.api.integradores().subscribe({ next: (l) => this.lista.set(l), error: () => this.erro.set('Não foi possível carregar os sistemas.') });
  }

  protected criar(): void {
    this.api.criarIntegrador(this.nome()).subscribe({ next: () => { this.nome.set(''); this.recarregar(); }, error: () => this.erro.set('Não foi possível cadastrar (o nome já existe?).') });
  }

  protected alternar(i: Integrador): void {
    this.api.alterarIntegrador(i.id, !i.ativo).subscribe({ next: () => this.recarregar(), error: () => this.erro.set('Não foi possível alterar.') });
  }

  protected abrir(i: Integrador): void {
    this.aberto.set(i);
    this.base.set(i.webhookBaseUrl ?? '');
    this.webhookAtivo.set(i.webhookAtivo);
    this.novoSegredo.set(false);
    this.api.chaves(i.id).subscribe({ next: (c) => this.chaves.set(c), error: () => this.erro.set('Não foi possível listar as keys.') });
  }

  protected marcar(e: string): void {
    this.escoposMarcados.update((l) => (l.includes(e) ? l.filter((x) => x !== e) : [...l, e]));
  }

  protected criarChave(): void {
    const i = this.aberto();
    if (!i) return;
    this.api.criarChave(i.id, this.nomeKey(), this.escoposMarcados()).subscribe({
      next: (k) => { this.segredoNovo.set({ rotulo: 'API key', valor: k.chave }); this.nomeKey.set(''); this.abrir(i); },
      error: () => this.erro.set('Não foi possível gerar a key.'),
    });
  }

  protected revogar(k: ChaveApi): void {
    const i = this.aberto();
    this.api.revogarChave(k.id).subscribe({ next: () => i && this.abrir(i), error: () => this.erro.set('Não foi possível revogar.') });
  }

  protected salvarWebhook(): void {
    const i = this.aberto();
    if (!i) return;
    this.api.webhook(i.id, this.base().trim() || null, this.webhookAtivo(), this.novoSegredo()).subscribe({
      next: (r) => {
        if (r.segredo) this.segredoNovo.set({ rotulo: 'Segredo de assinatura do webhook', valor: r.segredo });
        this.recarregar();
        this.abrir(r.integrador);
      },
      error: () => this.erro.set('Webhook recusado: a URL base precisa ser HTTPS, sem usuário, parâmetros nem fragmento.'),
    });
  }
}
