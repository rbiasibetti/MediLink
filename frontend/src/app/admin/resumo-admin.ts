import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { AdminApi, Painel } from '../core/admin-api';
import { Grafico, PALETA } from '../core/grafico';

const PODEM_REENVIAR = ['FALHA_NO_ENVIO', 'FALHA_NO_WORKER'];
const POR_PAGINA = 5;

/** Andamento da plataforma em gráficos: só contagens e estados, nunca dado de paciente (T18). */
@Component({
  selector: 'app-resumo-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, Grafico],
  template: `
    <div class="acoes">
      <label for="dias">Período</label>
      <select id="dias" class="form-select w-auto" (change)="mudarPeriodo($any($event.target).value)">
        <option value="7">7 dias</option><option value="30" selected>30 dias</option><option value="90">90 dias</option>
      </select>
      <button class="btn secundario" type="button" (click)="carregar()"><i class="bi bi-arrow-clockwise" aria-hidden="true"></i> Atualizar</button>
    </div>
    @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
    @if (painel(); as p) {
      <p class="muted">Gerado em {{ p.geradoEm | date: 'dd/MM/yyyy HH:mm:ss' }}</p>
      <div class="grade">
        <section class="card"><h2>Agendamentos</h2>
          <p><strong>{{ p.agendamentos.criadosNoPeriodo }}</strong> criados nos últimos {{ p.periodoDias }} dias</p>
          <app-grafico titulo="Agendamentos" [labels]="rotulos(p.agendamentos.porStatus)" [dados]="valores(p.agendamentos.porStatus)" />
          <ul class="legenda">@for (e of legenda(p.agendamentos.porStatus); track e.rotulo) { <li><span class="swatch" [style.background]="e.cor"></span>{{ e.rotulo }}: {{ e.valor }}</li> } @empty { <li class="muted">Nenhum.</li> }</ul>
        </section>
        <section class="card"><h2>Consultas</h2>
          <p><strong>{{ p.consultas.encerradasNoPeriodo }}</strong> encerradas no período</p>
          <app-grafico titulo="Consultas" [labels]="rotulos(p.consultas.porStatus)" [dados]="valores(p.consultas.porStatus)" />
          <ul class="legenda">@for (e of legenda(p.consultas.porStatus); track e.rotulo) { <li><span class="swatch" [style.background]="e.cor"></span>{{ e.rotulo }}: {{ e.valor }}</li> } @empty { <li class="muted">Nenhuma.</li> }</ul>
        </section>
        <section class="card"><h2>Transcrição</h2>
          <p><strong>{{ p.transcricao.transcricoesGravadas }}</strong> gravadas</p>
          <app-grafico titulo="Transcrição" tipo="bar" [labels]="rotulos(p.transcricao.jobsPorStatus)" [dados]="valores(p.transcricao.jobsPorStatus)" />
          <ul class="legenda">@for (e of legenda(p.transcricao.jobsPorStatus); track e.rotulo) { <li><span class="swatch" [style.background]="e.cor"></span>{{ e.rotulo }}: {{ e.valor }}</li> } @empty { <li class="muted">Nenhuma.</li> }</ul>
          <p class="muted">Áudio ainda no disco: {{ p.transcricao.audioAindaNoDisco }}
            @if (p.transcricao.audioMaisAntigoDesde) { (o mais antigo é de {{ p.transcricao.audioMaisAntigoDesde | date: 'dd/MM HH:mm' }}) }</p>
        </section>
        <section class="card"><h2>Webhooks</h2>
          <app-grafico titulo="Webhooks" [labels]="rotulos(p.webhooks)" [dados]="valores(p.webhooks)" />
          <ul class="legenda">@for (e of legenda(p.webhooks); track e.rotulo) { <li><span class="swatch" [style.background]="e.cor"></span>{{ e.rotulo }}: {{ e.valor }}</li> } @empty { <li class="muted">Nenhuma entrega.</li> }</ul>
        </section>
      </div>

      <h2>Por sistema integrado</h2>
      <div class="table-responsive"><table class="table table-hover table-striped align-middle"><thead><tr><th>Sistema</th><th>Total</th><th>No período</th></tr></thead>
        <tbody>@for (s of sistemas(p); track s.sistema) { <tr><td>{{ s.sistema }}</td><td>{{ s.total }}</td><td>{{ s.noPeriodo }}</td></tr> } @empty { <tr><td colspan="3" class="muted">Nenhum.</td></tr> }</tbody></table></div>
      @if (p.agendamentos.porSistema.length > tam) {
        <div class="paginacao">
          <span class="muted">{{ p.agendamentos.porSistema.length }} sistema(s) · página {{ pagSis() + 1 }} de {{ totalPag(p.agendamentos.porSistema.length) }}</span>
          <button class="btn-icone" type="button" [disabled]="pagSis() === 0" (click)="pagSis.set(pagSis() - 1)" title="Anterior" aria-label="Página anterior (sistemas)"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
          <button class="btn-icone" type="button" [disabled]="pagSis() + 1 >= totalPag(p.agendamentos.porSistema.length)" (click)="pagSis.set(pagSis() + 1)" title="Próxima" aria-label="Próxima página (sistemas)"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
        </div>
      }

      <h2>Incidentes</h2>
      <p class="muted">Consulta que terminou sem transcrição por motivo técnico. Toda consulta precisa ser registrada, então cada uma deve ser investigada.</p>
      @if (p.incidentes.length === 0) { <p>Nenhum incidente.</p> } @else {
        <div class="table-responsive"><table class="table table-hover table-striped align-middle"><thead><tr><th>Consulta</th><th>Tipo</th><th>Desde</th><th>Erro</th><th class="text-end">Ações</th></tr></thead>
          <tbody>@for (i of incidentes(p); track i.consultaId) {
            <tr><td><code>{{ i.consultaId }}</code></td><td>{{ i.tipo }}</td><td>{{ i.desde | date: 'dd/MM HH:mm' }}</td><td>{{ i.erro }}</td>
              <td class="text-end">@if (podeReenviar(i.tipo)) { <button class="btn-icone" type="button" (click)="reenviar(i.consultaId)" title="Reenviar" aria-label="Reenviar"><i class="bi bi-arrow-repeat" aria-hidden="true"></i></button> }</td></tr> }</tbody></table></div>
        @if (p.incidentes.length > tam) {
          <div class="paginacao">
            <span class="muted">{{ p.incidentes.length }} incidente(s) · página {{ pagInc() + 1 }} de {{ totalPag(p.incidentes.length) }}</span>
            <button class="btn-icone" type="button" [disabled]="pagInc() === 0" (click)="pagInc.set(pagInc() - 1)" title="Anterior" aria-label="Página anterior (incidentes)"><i class="bi bi-chevron-left" aria-hidden="true"></i></button>
            <button class="btn-icone" type="button" [disabled]="pagInc() + 1 >= totalPag(p.incidentes.length)" (click)="pagInc.set(pagInc() + 1)" title="Próxima" aria-label="Próxima página (incidentes)"><i class="bi bi-chevron-right" aria-hidden="true"></i></button>
          </div>
        }
      }
    } @else if (!erro()) { <p role="status">Carregando…</p> }
  `,
})
export class ResumoAdmin {
  private readonly api = inject(AdminApi);
  protected readonly painel = signal<Painel | null>(null);
  protected readonly erro = signal<string | null>(null);
  protected readonly pagSis = signal(0);
  protected readonly pagInc = signal(0);
  protected readonly tam = POR_PAGINA;
  private dias = 30;

  constructor() {
    this.carregar();
  }

  protected rotulos(m: Record<string, number>): string[] {
    return Object.keys(m);
  }

  protected valores(m: Record<string, number>): number[] {
    return Object.values(m);
  }

  protected legenda(m: Record<string, number>): { rotulo: string; valor: number; cor: string }[] {
    return Object.entries(m).map(([rotulo, valor], i) => ({ rotulo, valor, cor: PALETA[i % PALETA.length] }));
  }

  protected totalPag(total: number): number {
    return Math.max(1, Math.ceil(total / POR_PAGINA));
  }

  protected sistemas(p: Painel) {
    return p.agendamentos.porSistema.slice(this.pagSis() * POR_PAGINA, this.pagSis() * POR_PAGINA + POR_PAGINA);
  }

  protected incidentes(p: Painel) {
    return p.incidentes.slice(this.pagInc() * POR_PAGINA, this.pagInc() * POR_PAGINA + POR_PAGINA);
  }

  protected mudarPeriodo(v: string): void {
    this.dias = Number(v);
    this.carregar();
  }

  protected carregar(): void {
    this.erro.set(null);
    this.pagSis.set(0);
    this.pagInc.set(0);
    this.api.painel(this.dias).subscribe({ next: (p) => this.painel.set(p), error: () => this.erro.set('Não foi possível carregar o painel. Entre de novo se a sessão expirou.') });
  }

  protected podeReenviar(tipo: string): boolean {
    return PODEM_REENVIAR.includes(tipo);
  }

  protected reenviar(consultaId: string): void {
    this.api.reenviarJob(consultaId).subscribe({ next: () => this.carregar(), error: () => this.erro.set('Não foi possível reenviar (o áudio pode já ter sido apagado).') });
  }
}
