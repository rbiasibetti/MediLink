import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { retry } from 'rxjs';
import { Api, ConsultaDetalhe, Sessao } from '../core/api';
import { INTERVALO_ESPERA_MS } from '../core/config';
import { JitsiRoom } from '../core/jitsi-room';

type Estado = 'carregando' | 'sala' | 'saiu' | 'erro';

/**
 * Sala do médico (host). Mostra se há paciente aguardando e o consentimento, aceita o paciente e, ao sair da
 * conferência, encerra a consulta no backend, mas só se ela estava em andamento: sair antes de admitir
 * ninguém não encerra nada.
 */
@Component({
  selector: 'app-consulta-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [JitsiRoom, RouterLink],
  template: `
    <main class="pagina larga">
      @switch (estado()) {
        @case ('carregando') { <p role="status">Preparando a sala…</p> }
        @case ('erro') {
          <section class="card">
            <h1>Não foi possível abrir a sala</h1>
            <p>Verifique o login e se o agendamento é seu.</p>
            <a class="btn" routerLink="/medico/agendar">Voltar</a>
          </section>
        }
        @case ('sala') {
          @if (sessao(); as s) {
            <div class="barra" aria-live="polite">
              @if (detalhe(); as d) {
                @if (d.status === 'EM_ANDAMENTO') {
                  <span>Consulta em andamento.</span>
                } @else if (d.pacienteAguardando) {
                  <span><strong>Paciente aguardando.</strong></span>
                  <button class="btn" type="button" (click)="aceitar()" [disabled]="aceitando() || !salaPronta()">Aceitar paciente</button>
                  @if (!salaPronta()) { <span class="muted">Preparando a sala…</span> }
                } @else {
                  <span>Aguardando o paciente chegar…</span>
                }
                @if (d.status === 'EM_ANDAMENTO' && !d.capturaAutorizada) {
                  <span class="erro" role="alert"><strong>Atenção: esta consulta não está sendo registrada</strong> (o paciente retirou o consentimento). Encerre a consulta.</span>
                } @else {
                  <span class="muted">
                    @if (d.capturaAutorizada) { Consulta sendo registrada, com o consentimento do paciente. }
                    @else { Paciente com termo aceito; o registro começa com a consulta. }
                  </span>
                }
              }
            </div>
            <div class="jitsi-wrap">
              <app-jitsi-room [roomName]="s.roomName" [jwt]="s.jwt!" papel="medico" [transcrever]="detalhe()?.capturaAutorizada === true" (pronto)="salaPronta.set(true)" (participante)="registrarParticipante($event)" (saiu)="aoSair()" />
            </div>
          }
        }
        @case ('saiu') {
          <section class="card">
            @if (detalhe()?.status === 'ENCERRADA') {
              <h1>Consulta encerrada</h1>
              <p>A transcrição será gerada e salva no prontuário após o processamento.</p>
              @if (detalhe()?.podeReabrir) {
                <p class="muted">Encerrou por engano ou a conexão caiu? Dentro de poucos minutos você ainda pode voltar à mesma consulta.</p>
                <button class="btn" type="button" (click)="reabrir()">Reabrir a consulta</button>
              }
              <a class="btn secundario" [routerLink]="['/medico/transcricao', detalhe()?.id]">Ver a transcrição</a>
            } @else {
              <h1>Você saiu da sala</h1>
              <p>A consulta ainda não começou, então não foi encerrada.</p>
              <button class="btn" type="button" (click)="voltar()">Voltar para a sala</button>
            }
            <a class="btn secundario" routerLink="/medico/agendar">Novo agendamento</a>
          </section>
        }
      }
    </main>
  `,
})
export class ConsultaMedico {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);

  protected readonly estado = signal<Estado>('carregando');
  protected readonly sessao = signal<Sessao | null>(null);
  protected readonly detalhe = signal<ConsultaDetalhe | null>(null);
  protected readonly aceitando = signal(false);
  /** A sala já foi configurada ao entrar (e, se o flag estiver ligado, a E2EE acionada); só então o paciente é admitido. */
  protected readonly salaPronta = signal(false);
  private espera: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.parar());
    const id = this.route.snapshot.paramMap.get('agendamentoId') ?? '';
    this.api.tokenMedico(id).subscribe({
      next: (s) => {
        this.sessao.set(s);
        this.estado.set('sala');
        this.acompanhar();
      },
      error: () => this.estado.set('erro'),
    });
  }

  /** Atualiza o estado da consulta enquanto a sala está aberta. */
  private acompanhar(): void {
    const s = this.sessao();
    if (!s || this.estado() !== 'sala') return;
    this.api.detalhe(s.consultaId).subscribe({
      next: (d) => this.detalhe.set(d),
      complete: () => { this.espera = setTimeout(() => this.acompanhar(), INTERVALO_ESPERA_MS); },
      error: () => { this.espera = setTimeout(() => this.acompanhar(), INTERVALO_ESPERA_MS); },
    });
  }

  /** Informa o endpoint do Jitsi ao backend (rotula o áudio do médico). Falha não atrapalha a consulta. */
  protected registrarParticipante(endpointId: string): void {
    const s = this.sessao();
    if (!s) return;
    this.api.registrarParticipanteMedico(s.consultaId, endpointId).pipe(retry({ count: 3, delay: 2000 })).subscribe({ error: () => undefined });
  }

  protected aceitar(): void {
    const s = this.sessao();
    if (!s) return;
    this.aceitando.set(true);
    this.api.aceitar(s.consultaId).subscribe({
      next: (d) => { this.detalhe.set(d); this.aceitando.set(false); },
      error: () => this.aceitando.set(false),
    });
  }

  /** Volta a uma consulta encerrada há pouco (T17): reabre no backend e entra de novo na sala. */
  protected reabrir(): void {
    const s = this.sessao();
    if (!s) return;
    this.api.reabrir(s.consultaId).subscribe({
      next: (d) => {
        this.detalhe.set(d);
        this.salaPronta.set(false);
        this.api.tokenMedico(this.route.snapshot.paramMap.get('agendamentoId') ?? '').subscribe({
          next: (nova) => { this.sessao.set(nova); this.estado.set('sala'); this.acompanhar(); },
          error: () => this.estado.set('erro'),
        });
      },
      error: () => undefined, // o prazo terminou: a tela segue mostrando a consulta encerrada
    });
  }

  /** Evento de saída do Jitsi: lê o estado atual e encerra só se a consulta estava em andamento. */
  protected aoSair(): void {
    const s = this.sessao();
    if (!s) return;
    this.parar();
    this.api.detalhe(s.consultaId).subscribe({
      next: (d) => {
        if (d.status === 'EM_ANDAMENTO') {
          this.api.encerrar(s.consultaId).subscribe({
            next: (e) => { this.detalhe.set(e); this.estado.set('saiu'); },
            error: () => this.estado.set('saiu'),
          });
        } else {
          this.detalhe.set(d);
          this.estado.set('saiu');
        }
      },
      error: () => this.estado.set('saiu'),
    });
  }

  protected voltar(): void {
    this.salaPronta.set(false);
    this.estado.set('sala');
    this.acompanhar();
  }

  private parar(): void {
    if (this.espera) clearTimeout(this.espera);
    this.espera = null;
  }
}
