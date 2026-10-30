import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Api, TranscricaoLida } from '../core/api';

type Estado = 'carregando' | 'processando' | 'pronta' | 'sem-audio' | 'aberta' | 'falhou' | 'expirou' | 'negado';

/** Leitura da transcrição pelo médico dono (T13/T17). Enquanto o worker processa, consulta de novo a cada poucos segundos. */
@Component({
  selector: 'app-transcricao-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="pagina larga">
      <section class="card">
        <h1>Transcrição da consulta</h1>
        @switch (estado()) {
          @case ('carregando') { <p role="status">Carregando…</p> }
          @case ('processando') { <p role="status">A transcrição ainda está sendo gerada. Esta página se atualiza sozinha.</p> }
          @case ('aberta') { <p>A consulta ainda não foi encerrada.</p> }
          @case ('sem-audio') { <p>Esta consulta não teve áudio capturado: não há transcrição.</p> }
          @case ('falhou') { <p>A transcrição falhou e está sob análise.</p> }
          @case ('expirou') { <p>A transcrição desta consulta não está mais disponível (prazo de retenção).</p> }
          @case ('negado') { <p>Você não tem acesso a esta consulta, ou o link expirou.</p> }
          @case ('pronta') {
            @if (transcricao(); as t) {
              <p class="muted">Idioma: {{ t.idioma }} · duração: {{ duracao(t) }}</p>
              <ol class="falas">
                @for (s of t.segmentos; track $index) {
                  <li><strong>{{ rotulo(s.papel) }}</strong> <span class="muted">({{ tempo(s.inicioMs) }})</span>: {{ s.texto }}</li>
                }
              </ol>
            }
          }
        }
      </section>
    </main>
  `,
})
export class TranscricaoMedico {
  private readonly api = inject(Api);
  private readonly consultaId = inject(ActivatedRoute).snapshot.paramMap.get('consultaId') ?? '';
  protected readonly estado = signal<Estado>('carregando');
  protected readonly transcricao = signal<TranscricaoLida | null>(null);
  private espera: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => { if (this.espera) clearTimeout(this.espera); });
    this.buscar();
  }

  private buscar(): void {
    this.api.transcricao(this.consultaId).subscribe({
      next: (r) => {
        if (r.status === 200) {
          this.transcricao.set(r.body as TranscricaoLida);
          this.estado.set('pronta');
        } else {
          this.estado.set('processando');
          this.espera = setTimeout(() => this.buscar(), 5000);
        }
      },
      error: (e: HttpErrorResponse) => this.estado.set(
        e.status === 409 ? (e.error?.detail?.includes('falhou') ? 'falhou' : 'aberta')
          : e.status === 404 && e.error?.detail?.includes('áudio') ? 'sem-audio'
          : e.status === 410 ? 'expirou' : 'negado'),
    });
  }

  protected rotulo(papel: string): string {
    return papel === 'MEDICO' ? 'Médico' : papel === 'PACIENTE' ? 'Paciente' : 'Participante';
  }

  protected tempo(ms: number): string {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }

  protected duracao(t: TranscricaoLida): string {
    return this.tempo(t.duracaoMs);
  }
}
