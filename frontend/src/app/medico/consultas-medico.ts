import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, ConsultaResumo, PaginaConsultas, TranscricaoEstado } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';

const STATUS: Record<string, string> = {
  CRIADA: 'Agendada', AGUARDANDO_PACIENTE: 'Paciente aguardando', EM_ANDAMENTO: 'Em andamento', ENCERRADA: 'Encerrada', TRANSCRITA: 'Transcrita',
};
const TRANSCRICAO: Record<TranscricaoEstado, string> = {
  NAO_APLICAVEL: '—', EM_PROCESSAMENTO: 'Em processamento', DISPONIVEL: 'Disponível', SEM_AUDIO: 'Sem áudio', FALHA: 'Falhou', EXPIRADA: 'Expirada',
};

/** Consultas do médico (T20): da mais recente para a mais antiga, com o acesso à sala e à transcrição de cada uma. */
@Component({
  selector: 'app-consultas-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, RouterLink],
  template: `
    <main class="pagina larga">
      <header class="barra">
        <nav aria-label="Médico" class="acoes">
          <a class="btn" routerLink="/medico/consultas">Minhas consultas</a>
          <a class="btn secundario" routerLink="/medico/agendar">Agendar</a>
        </nav>
        <button class="link" type="button" (click)="sair()">Sair</button>
      </header>
      <section class="card">
        <h1>Minhas consultas</h1>
        <label for="filtro">Situação</label>
        <select id="filtro" (change)="filtrar($any($event.target).value)">
          <option value="">Todas</option>
          @for (s of situacoes; track s.id) { <option [value]="s.id">{{ s.rotulo }}</option> }
        </select>
        @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
        @if (pagina(); as p) {
          @if (p.itens.length === 0) { <p class="muted">Nenhuma consulta encontrada.</p> }
          @else {
            <table>
              <thead><tr><th>Data</th><th>Paciente</th><th>Situação</th><th>Transcrição</th><th></th></tr></thead>
              <tbody>
                @for (c of p.itens; track c.consultaId) {
                  <tr>
                    <td>{{ c.dataHora | date: 'dd/MM/yyyy HH:mm' }}</td>
                    <td>{{ c.pacienteNome }}</td>
                    <td>{{ rotuloStatus(c.status) }}</td>
                    <td>{{ rotuloTranscricao(c.transcricao) }}</td>
                    <td>
                      @if (c.transcricao === 'DISPONIVEL' || c.transcricao === 'EM_PROCESSAMENTO') {
                        <a class="btn secundario" [routerLink]="['/medico/transcricao', c.consultaId]">Ver transcrição</a>
                      }
                      @if (aberta(c)) {
                        <a class="btn secundario" [routerLink]="['/medico/consulta', c.agendamentoId]">Entrar na sala</a>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
            <p class="muted">Página {{ p.pagina + 1 }} de {{ totalPaginas(p) }} · {{ p.total }} consulta(s)</p>
            <div class="acoes">
              <button class="btn secundario" type="button" [disabled]="p.pagina === 0" (click)="ir(p.pagina - 1)">Anterior</button>
              <button class="btn secundario" type="button" [disabled]="p.pagina + 1 >= totalPaginas(p)" (click)="ir(p.pagina + 1)">Próxima</button>
            </div>
          }
        } @else if (!erro()) { <p role="status">Carregando…</p> }
      </section>
    </main>
  `,
})
export class ConsultasMedico {
  private readonly api = inject(Api);
  private readonly auth = inject(MedicoAuth);
  private readonly router = inject(Router);
  protected readonly situacoes = Object.entries(STATUS).map(([id, rotulo]) => ({ id, rotulo }));
  protected readonly pagina = signal<PaginaConsultas | null>(null);
  protected readonly erro = signal<string | null>(null);
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

  protected aberta(c: ConsultaResumo): boolean {
    return c.status === 'CRIADA' || c.status === 'AGUARDANDO_PACIENTE' || c.status === 'EM_ANDAMENTO';
  }

  protected rotuloStatus(s: string): string {
    return STATUS[s] ?? s;
  }

  protected rotuloTranscricao(s: TranscricaoEstado): string {
    return TRANSCRICAO[s] ?? s;
  }

  protected sair(): void {
    this.auth.sair();
    void this.router.navigate(['/medico']);
  }
}
