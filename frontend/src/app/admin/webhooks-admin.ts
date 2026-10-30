import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AdminApi, Entrega } from '../core/admin-api';

/** Estado das entregas de webhook (sem o corpo, que tem o texto da consulta) e reenvio das que falharam (T19). */
@Component({
  selector: 'app-webhooks-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  template: `
    <h2>Entregas de webhook</h2>
    <div class="acoes">
      <label for="filtro">Situação</label>
      <select id="filtro" (change)="filtrar($any($event.target).value)">
        <option value="">todas</option><option value="PENDENTE">pendentes</option><option value="FALHA">com falha</option><option value="ENTREGUE">entregues</option>
      </select>
      <button class="btn secundario" type="button" (click)="carregar()">Atualizar</button>
    </div>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    <table><thead><tr><th>Evento</th><th>Situação</th><th>Tentativas</th><th>HTTP</th><th>Erro</th><th>Criada</th><th></th></tr></thead>
      <tbody>@for (e of lista(); track e.id) {
        <tr><td>{{ e.evento }}@if (e.motivo) { ({{ e.motivo }}) }</td><td>{{ e.status }}</td><td>{{ e.tentativas }}</td><td>{{ e.ultimoStatusHttp ?? '—' }}</td>
          <td>{{ e.ultimoErro ?? '—' }}</td><td>{{ e.criadoEm | date: 'dd/MM HH:mm' }}</td>
          <td>@if (e.status === 'FALHA') { <button class="btn secundario" type="button" (click)="reenviar(e)">Reenviar</button> }</td></tr>
      } @empty { <tr><td colspan="7" class="muted">Nenhuma entrega.</td></tr> }</tbody></table>
  `,
})
export class WebhooksAdmin {
  private readonly api = inject(AdminApi);
  protected readonly lista = signal<Entrega[]>([]);
  protected readonly erro = signal<string | null>(null);
  private status = '';

  constructor() {
    this.carregar();
  }

  protected filtrar(s: string): void {
    this.status = s;
    this.carregar();
  }

  protected carregar(): void {
    this.erro.set(null);
    this.api.entregas(this.status || undefined).subscribe({ next: (l) => this.lista.set(l), error: () => this.erro.set('Não foi possível carregar as entregas.') });
  }

  protected reenviar(e: Entrega): void {
    this.api.reenviarEntrega(e.id).subscribe({ next: () => this.carregar(), error: () => this.erro.set('Não foi possível reenviar.') });
  }
}
