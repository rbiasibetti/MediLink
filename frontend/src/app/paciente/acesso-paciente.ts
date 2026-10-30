import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { retry } from 'rxjs';
import { Acesso, Api, Sessao, Termo } from '../core/api';
import { INTERVALO_ESPERA_MS } from '../core/config';
import { JitsiRoom } from '../core/jitsi-room';

type Etapa = 'carregando' | 'indisponivel' | 'cpf' | 'espera' | 'sala' | 'saiu' | 'expirou';

/**
 * Jornada do paciente a partir do link da consulta:
 * (CPF, se o médico exigir) → consentimento → espera do aceite do médico → sala.
 * O CPF só trafega no corpo de um POST; nunca na URL, em log ou em storage.
 */
@Component({
  selector: 'app-acesso-paciente',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DatePipe, JitsiRoom],
  template: `
    <main class="pagina">
      @switch (etapa()) {
        @case ('carregando') { <p role="status">Carregando…</p> }

        @case ('indisponivel') {
          <section class="card">
            <h1>Consulta não encontrada</h1>
            <p>Confira o link recebido por e-mail. Se o problema continuar, fale com o consultório.</p>
          </section>
        }

        @case ('cpf') {
          <section class="card">
            <h1>Sua teleconsulta</h1>
            @if (acesso(); as a) {
              <p>Com <strong>{{ a.medicoNome }}</strong>, em {{ a.dataHora | date: 'dd/MM/yyyy \\'às\\' HH:mm' }}.</p>
            }
            <p>Para proteger a sua privacidade, confirme seu CPF para entrar.</p>
            <form (ngSubmit)="confirmarCpf()">
              <label for="cpf">CPF</label>
              <input id="cpf" name="cpf" inputmode="numeric" autocomplete="off" maxlength="14"
                     placeholder="000.000.000-00" [(ngModel)]="cpf" required />
              @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
              <button class="btn" type="submit" [disabled]="enviando() || !cpf()">Continuar</button>
            </form>
          </section>
        }

        @case ('expirou') {
          <section class="card">
            <h1>Sessão expirada</h1>
            <p>Por segurança, é preciso confirmar o acesso de novo. Se a consulta já terminou, pode fechar esta página.</p>
            <button class="btn" type="button" (click)="reiniciar()">Confirmar novamente</button>
          </section>
        }

        @case ('espera') {
          <section class="card">
            <h1>Sala de espera</h1>
            @if (termo(); as t) {
              @if (decisao() === null) {
                <h2>Consentimento</h2>
                <pre class="termo" tabindex="0" aria-label="Texto do termo de consentimento">{{ t.texto }}</pre>
                @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
                <div class="acoes">
                  <button class="btn" type="button" (click)="decidir(true)" [disabled]="!consultaId() || enviando()">Aceito</button>
                  <button class="btn secundario" type="button" (click)="decidir(false)" [disabled]="!consultaId() || enviando()">Não aceito</button>
                </div>
                <p class="muted">Recusar não impede o atendimento; a consulta acontece sem transcrição.</p>
              } @else {
                <p role="status" aria-live="polite">
                  Aguardando o médico liberar a sua entrada. Mantenha esta página aberta.
                </p>
                <p class="muted">
                  @if (decisao() === 'aceito') { Consentimento registrado. } @else { Você não autorizou a transcrição. }
                </p>
              }
            } @else {
              <p role="status">Carregando…</p>
            }
          </section>
        }

        @case ('sala') {
          @if (sessao(); as s) {
            <div class="barra">
              @if (decisao() === 'aceito') {
                <span>Transcrição autorizada.</span>
                <button class="link" type="button" (click)="retirarConsentimento()">Retirar consentimento</button>
              } @else {
                <span>Transcrição não autorizada.</span>
              }
            </div>
            <div class="jitsi-wrap">
              <app-jitsi-room [roomName]="s.roomName" [jwt]="s.jwt!" papel="paciente" (participante)="registrarParticipante($event)" (saiu)="aoSair()" />
            </div>
          }
        }

        @case ('saiu') {
          <section class="card">
            <h1>Você saiu da consulta</h1>
            <button class="btn" type="button" (click)="voltarParaSala()">Voltar para a sala</button>
          </section>
        }
      }
    </main>
  `,
})
export class AcessoPaciente {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(Api);

  protected readonly room = this.route.snapshot.paramMap.get('roomName') ?? '';
  protected readonly etapa = signal<Etapa>('carregando');
  protected readonly acesso = signal<Acesso | null>(null);
  protected readonly cpf = signal('');
  protected readonly erro = signal<string | null>(null);
  protected readonly enviando = signal(false);
  protected readonly termo = signal<Termo | null>(null);
  protected readonly decisao = signal<'aceito' | 'recusado' | null>(null);
  protected readonly consultaId = signal<string | null>(null);
  protected readonly sessao = signal<Sessao | null>(null);
  protected readonly liberado = computed(() => this.sessao() !== null && this.decisao() !== null);

  private tokenEntrada: string | null = null;
  private espera: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.pararEspera());
    this.carregar();
  }

  protected reiniciar(): void {
    this.pararEspera();
    this.sessao.set(null);
    this.decisao.set(null);
    this.erro.set(null);
    this.etapa.set('carregando');
    this.carregar();
  }

  private carregar(): void {
    this.api.acesso(this.room).subscribe({
      next: (a) => {
        this.acesso.set(a);
        if (a.cpfObrigatorio) this.etapa.set('cpf');
        else this.entrar(null);
      },
      error: () => this.etapa.set('indisponivel'),
    });
  }

  protected confirmarCpf(): void {
    if (this.cpf().trim()) this.entrar(this.cpf());
  }

  private entrar(cpf: string | null): void {
    this.enviando.set(true);
    this.erro.set(null);
    this.api.validarCpf(this.room, cpf).subscribe({
      next: (e) => {
        this.enviando.set(false);
        this.cpf.set('');
        this.tokenEntrada = e.tokenEntrada;
        this.iniciarEspera();
      },
      error: (e: HttpErrorResponse) => {
        this.enviando.set(false);
        this.erro.set(e.status === 429
          ? 'Muitas tentativas. Aguarde alguns minutos e tente novamente.'
          : 'Não foi possível confirmar o seu acesso. Confira o CPF e tente novamente.');
        this.etapa.set(cpf === null ? 'indisponivel' : 'cpf');
      },
    });
  }

  private iniciarEspera(): void {
    this.etapa.set('espera');
    this.api.termo().subscribe({ next: (t) => this.termo.set(t), error: () => this.erro.set('Não foi possível carregar o termo.') });
    this.consultar();
  }

  /** Repete a chamada até o médico aceitar; cada resposta 202 mantém o paciente em espera. */
  private consultar(): void {
    this.api.tokenPaciente(this.room, this.tokenEntrada!).subscribe({
      next: (s) => {
        this.consultaId.set(s.consultaId);
        if (s.estado === 'ADMITIDO') {
          this.sessao.set(s);
          this.avancarSeLiberado();
        } else {
          this.espera = setTimeout(() => this.consultar(), INTERVALO_ESPERA_MS);
        }
      },
      error: () => this.etapa.set('expirou'),
    });
  }

  protected decidir(aceito: boolean): void {
    const t = this.termo();
    const c = this.consultaId();
    if (!t || !c || !this.tokenEntrada) return;
    this.enviando.set(true);
    this.erro.set(null);
    this.api.consentir(c, this.tokenEntrada, aceito, t.versao).subscribe({
      next: () => {
        this.enviando.set(false);
        this.decisao.set(aceito ? 'aceito' : 'recusado');
        this.avancarSeLiberado();
      },
      error: (e: HttpErrorResponse) => {
        this.enviando.set(false);
        this.erro.set(e.status === 409 ? 'O termo foi atualizado. Recarregue a página para lê-lo.' : 'Não foi possível registrar a sua resposta. Tente de novo.');
      },
    });
  }

  /** Retirada do consentimento durante a consulta: a captação deixa de ser autorizada. */
  protected retirarConsentimento(): void {
    const t = this.termo();
    const c = this.consultaId();
    if (!t || !c || !this.tokenEntrada) return;
    this.api.consentir(c, this.tokenEntrada, false, t.versao).subscribe({ next: () => this.decisao.set('recusado') });
  }

  /** Só entra na sala admitido pelo médico E depois de ter respondido ao termo. */
  private avancarSeLiberado(): void {
    if (this.liberado()) this.etapa.set('sala');
  }

  /** Informa o endpoint do Jitsi ao backend (rotula o áudio do paciente). Falha não atrapalha a consulta. */
  protected registrarParticipante(endpointId: string): void {
    const c = this.consultaId();
    if (!c || !this.tokenEntrada) return;
    this.api.registrarParticipantePaciente(c, this.tokenEntrada, endpointId).pipe(retry({ count: 3, delay: 2000 })).subscribe({ error: () => undefined });
  }

  protected aoSair(): void {
    this.etapa.set('saiu');
  }

  protected voltarParaSala(): void {
    this.etapa.set('sala');
  }

  private pararEspera(): void {
    if (this.espera) clearTimeout(this.espera);
    this.espera = null;
  }
}
