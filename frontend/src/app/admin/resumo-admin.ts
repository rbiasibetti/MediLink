import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe, KeyValuePipe } from '@angular/common';
import { AdminApi, Painel } from '../core/admin-api';

const PODEM_REENVIAR = ['FALHA_NO_ENVIO', 'FALHA_NO_WORKER'];

/** Andamento da plataforma: só contagens e estados, nunca dado de paciente (T18). */
@Component({
  selector: 'app-resumo-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, KeyValuePipe],
  template: `
    <div class="acoes">
      <label for="dias">Período</label>
      <select id="dias" (change)="mudarPeriodo($any($event.target).value)">
        <option value="7">7 dias</option><option value="30" selected>30 dias</option><option value="90">90 dias</option>
      </select>
      <button class="btn secundario" type="button" (click)="carregar()">Atualizar</button>
    </div>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    @if (painel(); as p) {
      <p class="muted">Gerado em {{ p.geradoEm | date: 'dd/MM/yyyy HH:mm:ss' }}</p>
      <div class="grade">
        <section class="card"><h2>Agendamentos</h2>
          <p><strong>{{ p.agendamentos.criadosNoPeriodo }}</strong> criados nos últimos {{ p.periodoDias }} dias</p>
          <ul>@for (e of p.agendamentos.porStatus | keyvalue; track e.key) { <li>{{ e.key }}: {{ e.value }}</li> }</ul></section>
        <section class="card"><h2>Consultas</h2>
          <p><strong>{{ p.consultas.encerradasNoPeriodo }}</strong> encerradas no período</p>
          <ul>@for (e of p.consultas.porStatus | keyvalue; track e.key) { <li>{{ e.key }}: {{ e.value }}</li> }</ul></section>
        <section class="card"><h2>Transcrição</h2>
          <p><strong>{{ p.transcricao.transcricoesGravadas }}</strong> gravadas</p>
          <ul>@for (e of p.transcricao.jobsPorStatus | keyvalue; track e.key) { <li>{{ e.key }}: {{ e.value }}</li> }</ul>
          <p class="muted">Áudio ainda no disco: {{ p.transcricao.audioAindaNoDisco }}
            @if (p.transcricao.audioMaisAntigoDesde) { (o mais antigo é de {{ p.transcricao.audioMaisAntigoDesde | date: 'dd/MM HH:mm' }}) }</p></section>
        <section class="card"><h2>Webhooks</h2>
          <ul>@for (e of p.webhooks | keyvalue; track e.key) { <li>{{ e.key }}: {{ e.value }}</li> } @empty { <li class="muted">Nenhuma entrega.</li> }</ul></section>
      </div>
      <h2>Por sistema integrado</h2>
      <table><thead><tr><th>Sistema</th><th>Total</th><th>No período</th></tr></thead>
        <tbody>@for (s of p.agendamentos.porSistema; track s.sistema) { <tr><td>{{ s.sistema }}</td><td>{{ s.total }}</td><td>{{ s.noPeriodo }}</td></tr> }</tbody></table>
      <h2>Incidentes</h2>
      <p class="muted">Consulta que terminou sem transcrição por motivo técnico. Toda consulta precisa ser registrada, então cada uma deve ser investigada.</p>
      @if (p.incidentes.length === 0) { <p>Nenhum incidente.</p> } @else {
        <table><thead><tr><th>Consulta</th><th>Tipo</th><th>Desde</th><th>Erro</th><th></th></tr></thead>
          <tbody>@for (i of p.incidentes; track i.consultaId) {
            <tr><td><code>{{ i.consultaId }}</code></td><td>{{ i.tipo }}</td><td>{{ i.desde | date: 'dd/MM HH:mm' }}</td><td>{{ i.erro }}</td>
              <td>@if (podeReenviar(i.tipo)) { <button class="btn secundario" type="button" (click)="reenviar(i.consultaId)">Reenviar</button> }</td></tr> }</tbody></table>
      }
    } @else if (!erro()) { <p role="status">Carregando…</p> }
  `,
})
export class ResumoAdmin {
  private readonly api = inject(AdminApi);
  protected readonly painel = signal<Painel | null>(null);
  protected readonly erro = signal<string | null>(null);
  private dias = 30;

  constructor() {
    this.carregar();
  }

  protected mudarPeriodo(v: string): void {
    this.dias = Number(v);
    this.carregar();
  }

  protected carregar(): void {
    this.erro.set(null);
    this.api.painel(this.dias).subscribe({ next: (p) => this.painel.set(p), error: () => this.erro.set('Não foi possível carregar o painel. Entre de novo se a sessão expirou.') });
  }

  protected podeReenviar(tipo: string): boolean {
    return PODEM_REENVIAR.includes(tipo);
  }

  protected reenviar(consultaId: string): void {
    this.api.reenviarJob(consultaId).subscribe({ next: () => this.carregar(), error: () => this.erro.set('Não foi possível reenviar (o áudio pode já ter sido apagado).') });
  }
}
