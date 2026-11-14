import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, ConsultaResumo, PaginaConsultas, TranscricaoEstado } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';
import { MedicoLayout } from './medico-layout';

const STATUS: Record<string, string> = {
  CRIADA: 'Agendada', AGUARDANDO_PACIENTE: 'Paciente aguardando', EM_ANDAMENTO: 'Em andamento', ENCERRADA: 'Encerrada', TRANSCRITA: 'Transcrita',
};
const TRANSCRICAO: Record<TranscricaoEstado, string> = {
  NAO_APLICAVEL: '—', EM_PROCESSAMENTO: 'Em processamento', DISPONIVEL: 'Disponível', SEM_AUDIO: 'Sem áudio', FALHA: 'Falhou', EXPIRADA: 'Expirada',
};

/** Consultas do médico (T20): lista, acesso à sala e à transcrição, cancelamento e revisão do link do paciente. */
@Component({
  selector: 'app-consultas-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink, MedicoLayout],
  template: `
    <app-medico-layout>
      <section class="painel-card">
        <h1>Minhas consultas</h1>
        <label for="filtro">Situação</label>
        <select id="filtro" class="form-select w-auto" (change)="filtrar($any($event.target).value)">
          <option value="">Todas</option>
          @for (s of situacoes; track s.id) { <option [value]="s.id">{{ s.rotulo }}</option> }
        </select>
        @if (erro()) { <p class="erro mt-3" role="alert">{{ erro() }}</p> }
        @if (aviso()) { <p class="text-success mt-3" role="status">{{ aviso() }}</p> }
        @if (pagina(); as p) {
          @if (p.itens.length === 0) { <p class="muted mt-3">Nenhuma consulta encontrada.</p> }
          @else {
            <div class="table-responsive mt-3"><table class="table table-hover table-striped align-middle">
              <thead><tr><th>Data</th><th>Paciente</th><th>Situação</th><th>Transcrição</th><th class="text-end">Ações</th></tr></thead>
              <tbody>
                @for (c of p.itens; track c.consultaId) {
                  <tr>
                    <td>{{ c.dataHora | date: 'dd/MM/yyyy HH:mm' }}</td>
                    <td>{{ c.pacienteNome }}</td>
                    <td>{{ situacao(c) }}</td>
                    <td>{{ rotuloTranscricao(c.transcricao) }}</td>
                    <td class="text-end">
                      <div class="acoes-tabela justify-content-end">
                        @if (aberta(c)) {
                          <a class="btn-icone" [routerLink]="['/medico/consulta', c.agendamentoId]" title="Entrar na sala" aria-label="Entrar na sala"><i class="bi bi-camera-video" aria-hidden="true"></i></a>
                          <button class="btn-icone" type="button" (click)="verLink(c)" title="Rever o link do paciente" aria-label="Rever o link do paciente"><i class="bi bi-link-45deg" aria-hidden="true"></i></button>
                          <button class="btn-icone perigo" type="button" (click)="pedirCancelamento(c)" title="Cancelar consulta" aria-label="Cancelar consulta"><i class="bi bi-x-circle" aria-hidden="true"></i></button>
                        }
                        @if (p.leituraDeTranscricao && (c.transcricao === 'DISPONIVEL' || c.transcricao === 'EM_PROCESSAMENTO')) {
                          <a class="btn-icone" [routerLink]="['/medico/transcricao', c.consultaId]" title="Ver a transcrição" aria-label="Ver a transcrição"><i class="bi bi-file-earmark-text" aria-hidden="true"></i></a>
                        }
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table></div>
            @if (!p.leituraDeTranscricao) { <p class="muted" role="note">A leitura de transcrições por esta plataforma não está habilitada.</p> }
            <p class="muted">Página {{ p.pagina + 1 }} de {{ totalPaginas(p) }} · {{ p.total }} consulta(s)</p>
            <div class="acoes">
              <button class="btn secundario" type="button" [disabled]="p.pagina === 0" (click)="ir(p.pagina - 1)"><i class="bi bi-chevron-left" aria-hidden="true"></i> Anterior</button>
              <button class="btn secundario" type="button" [disabled]="p.pagina + 1 >= totalPaginas(p)" (click)="ir(p.pagina + 1)">Próxima <i class="bi bi-chevron-right" aria-hidden="true"></i></button>
            </div>
          }
        } @else if (!erro()) { <p role="status">Carregando…</p> }
      </section>
    </app-medico-layout>

    @if (link(); as l) {
      <div class="modal-fundo" role="dialog" aria-modal="true" aria-labelledby="tituloLink" (click)="fecharLink($event)">
        <div class="modal-caixa" (click)="$event.stopPropagation()">
          <h2 id="tituloLink">Link do paciente</h2>
          <p class="muted">Envie este link ao paciente para ele entrar na consulta de <strong>{{ l.nome }}</strong>.</p>
          <input class="form-control" readonly [value]="l.url" (focus)="$any($event.target).select()" aria-label="Link do paciente" />
          @if (copiado()) { <p class="text-success mt-2" role="status">Link copiado.</p> }
          <div class="acoes">
            <button class="btn" type="button" (click)="copiarLink(l.url)"><i class="bi bi-clipboard" aria-hidden="true"></i> Copiar link</button>
            <button class="btn secundario" type="button" (click)="link.set(null)">Fechar</button>
          </div>
        </div>
      </div>
    }

    @if (cancelando(); as c) {
      <div class="modal-fundo" role="dialog" aria-modal="true" aria-labelledby="tituloCancel" (click)="cancelando.set(null)">
        <div class="modal-caixa" (click)="$event.stopPropagation()">
          <h2 id="tituloCancel">Cancelar consulta</h2>
          <p>Cancelar a consulta de <strong>{{ c.pacienteNome }}</strong> de {{ c.dataHora | date: 'dd/MM/yyyy HH:mm' }}? O link do paciente deixará de funcionar.</p>
          @if (erroCancel()) { <p class="erro" role="alert">{{ erroCancel() }}</p> }
          <div class="acoes">
            <button class="btn" type="button" [disabled]="enviando()" (click)="confirmarCancelamento(c)"><i class="bi bi-x-circle" aria-hidden="true"></i> Cancelar a consulta</button>
            <button class="btn secundario" type="button" (click)="cancelando.set(null)">Voltar</button>
          </div>
        </div>
      </div>
    }
  `,
})
export class ConsultasMedico {
  private readonly api = inject(Api);
  private readonly auth = inject(MedicoAuth);
  private readonly router = inject(Router);
  protected readonly situacoes = Object.entries(STATUS).map(([id, rotulo]) => ({ id, rotulo }));
  protected readonly pagina = signal<PaginaConsultas | null>(null);
  protected readonly erro = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly link = signal<{ nome: string; url: string } | null>(null);
  protected readonly copiado = signal(false);
  protected readonly cancelando = signal<ConsultaResumo | null>(null);
  protected readonly erroCancel = signal<string | null>(null);
  protected readonly enviando = signal(false);
  private status = '';

  constructor() {
    this.ir(0);
  }

  protected ir(n: number): void {
    this.erro.set(null);
    this.api.consultas(n, this.status || undefined).subscribe({
      next: (p) => this.pagina.set(p),
      error: (e) => {
        if (e?.status === 401) {
          this.sair();
          return;
        }
        this.erro.set('Não foi possível carregar as consultas.');
      },
    });
  }

  protected filtrar(status: string): void {
    this.status = status;
    this.ir(0);
  }

  protected totalPaginas(p: PaginaConsultas): number {
    return Math.max(1, Math.ceil(p.total / p.tamanho));
  }

  /** Só consultas agendadas (não canceladas) que ainda não terminaram aceitam entrar na sala, rever o link e cancelar. */
  protected aberta(c: ConsultaResumo): boolean {
    return c.agendamentoStatus === 'AGENDADO' && (c.status === 'CRIADA' || c.status === 'AGUARDANDO_PACIENTE' || c.status === 'EM_ANDAMENTO');
  }

  protected situacao(c: ConsultaResumo): string {
    return c.agendamentoStatus === 'CANCELADO' ? 'Cancelada' : (STATUS[c.status] ?? c.status);
  }

  protected rotuloTranscricao(s: TranscricaoEstado): string {
    return TRANSCRICAO[s] ?? s;
  }

  protected verLink(c: ConsultaResumo): void {
    this.copiado.set(false);
    this.link.set({ nome: c.pacienteNome, url: `${location.origin}/sala/${c.roomName}` });
  }

  protected fecharLink(evento: Event): void {
    if (evento.target === evento.currentTarget) this.link.set(null);
  }

  protected copiarLink(url: string): void {
    navigator.clipboard?.writeText(url).then(() => this.copiado.set(true)).catch(() => this.copiado.set(false));
  }

  protected pedirCancelamento(c: ConsultaResumo): void {
    this.erroCancel.set(null);
    this.cancelando.set(c);
  }

  protected confirmarCancelamento(c: ConsultaResumo): void {
    this.enviando.set(true);
    this.erroCancel.set(null);
    this.api.cancelarConsulta(c.consultaId).subscribe({
      next: () => {
        this.enviando.set(false);
        this.cancelando.set(null);
        this.aviso.set(`Consulta de ${c.pacienteNome} cancelada.`);
        this.ir(this.pagina()?.pagina ?? 0);
      },
      error: (e) => {
        this.enviando.set(false);
        this.erroCancel.set(e?.status === 409 ? 'A consulta já começou e não pode mais ser cancelada.' : 'Não foi possível cancelar. Tente de novo.');
      },
    });
  }

  protected sair(): void {
    this.auth.sair();
    void this.router.navigate(['/medico']);
  }
}
