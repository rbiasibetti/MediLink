import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, input, output, signal, viewChild } from '@angular/core';
import { ALTURA_VIDEO_NIVEL_2, E2EE_ATIVA_POR_PADRAO, JITSI_DOMAIN } from './config';

export type Papel = 'medico' | 'paciente';

interface JitsiApi {
  addEventListener(evento: string, handler: () => void): void;
  executeCommand(comando: string, ...args: unknown[]): void;
  dispose(): void;
}
declare global {
  interface Window {
    JitsiMeetExternalAPI?: new (domain: string, opcoes: Record<string, unknown>) => JitsiApi;
  }
}

let carregamento: Promise<void> | null = null;

/** Carrega o external_api.js do próprio Jitsi, uma única vez. */
function carregarScript(dominio: string): Promise<void> {
  if (window.JitsiMeetExternalAPI) return Promise.resolve();
  carregamento ??= new Promise<void>((ok, falha) => {
    const s = document.createElement('script');
    s.src = `https://${dominio}/external_api.js`;
    s.onload = () => ok();
    s.onerror = () => { carregamento = null; falha(new Error('script do Jitsi indisponível')); };
    document.head.appendChild(s);
  });
  return carregamento;
}

/**
 * Embute a sala do Jitsi (IFrame External API) com a sala e o JWT emitidos pelo backend.
 *
 * Ao entrar na conferência (videoConferenceJoined) configura a sala:
 *  - qualidade de vídeo no nível 2 de desempenho (definição padrão) e supressão de ruído ligada, para todos;
 *  - criptografia de ponta a ponta, só se o flag `e2ee` estiver ligado (padrão: desligado, ver config.ts), e
 *    então só pelo médico (moderador); o paciente é admitido depois e a acompanha. Não há botão para
 *    desligá-la: a barra de ferramentas do servidor não inclui "security".
 * Depois disso emite `pronto`. Emite `saiu` uma vez quando o participante deixa a conferência.
 */
@Component({
  selector: 'app-jitsi-room',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (erro()) {
      <p class="erro" role="alert">
        Não foi possível carregar a videochamada. Confirme que o Jitsi está no ar e que o certificado de
        <a [href]="'https://' + dominio" target="_blank" rel="noopener">{{ dominio }}</a> foi aceito neste navegador.
      </p>
    }
    <div #container class="jitsi" [hidden]="erro()"></div>
  `,
})
export class JitsiRoom implements OnDestroy {
  readonly roomName = input.required<string>();
  readonly jwt = input.required<string>();
  readonly papel = input.required<Papel>();
  /** Liga a E2EE (só o médico a aciona). O padrão vem de E2EE_ATIVA_POR_PADRAO. */
  readonly e2ee = input(E2EE_ATIVA_POR_PADRAO);
  /**
   * Liga a exportação do áudio ao audio sink (só o médico). A página do médico passa true enquanto o backend diz
   * que a captura está autorizada (consulta em andamento e consentimento vigente). É só o gatilho: o sink
   * confere o consentimento por conta própria e descarta o áudio sem ele.
   */
  readonly transcrever = input(false);
  readonly pronto = output<void>();
  readonly saiu = output<void>();

  protected readonly dominio = JITSI_DOMAIN;
  protected readonly erro = signal(false);
  private readonly container = viewChild.required<ElementRef<HTMLElement>>('container');
  private api: JitsiApi | null = null;
  private emitiu = false;
  private entrou = false;
  private transcrevendo = false;

  constructor() {
    afterNextRender(() => void this.iniciar());
    effect(() => {
      const quer = this.transcrever();
      if (this.entrou) this.aplicarTranscricao(quer);
    });
  }

  private async iniciar(): Promise<void> {
    try {
      await carregarScript(this.dominio);
    } catch {
      this.erro.set(true);
      return;
    }
    const Api = window.JitsiMeetExternalAPI!;
    this.api = new Api(this.dominio, {
      roomName: this.roomName(),
      jwt: this.jwt(),
      parentNode: this.container().nativeElement,
      width: '100%',
      height: '100%',
      lang: 'ptBR',
      configOverwrite: { prejoinConfig: { enabled: false }, disableDeepLinking: true },
    });
    const sair = () => {
      if (!this.emitiu) {
        this.emitiu = true;
        this.saiu.emit();
      }
    };
    this.api.addEventListener('videoConferenceJoined', () => this.configurarSala());
    this.api.addEventListener('videoConferenceLeft', sair);
    this.api.addEventListener('readyToClose', sair);
  }

  /** Preferências da sala aplicadas ao entrar. Se a E2EE estiver ligada, só o moderador (médico) a aciona. */
  private configurarSala(): void {
    const api = this.api;
    if (!api) return;
    api.executeCommand('setVideoQuality', ALTURA_VIDEO_NIVEL_2);
    api.executeCommand('setNoiseSuppressionEnabled', { enabled: true });
    if (this.e2ee() && this.papel() === 'medico') {
      api.executeCommand('toggleE2EE', true);
    }
    this.entrou = true;
    this.aplicarTranscricao(this.transcrever());
    this.pronto.emit();
  }

  /** Liga/desliga a exportação do áudio no Jitsi; só envia quando o estado muda. */
  private aplicarTranscricao(ligar: boolean): void {
    if (!this.api || ligar === this.transcrevendo) return;
    this.transcrevendo = ligar;
    this.api.executeCommand('setSubtitles', ligar, false);
  }

  ngOnDestroy(): void {
    this.api?.dispose();
  }
}
