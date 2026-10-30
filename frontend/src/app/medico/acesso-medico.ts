import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';

/**
 * Entrada do médico por link de uso único (T17): {base}/medico/acesso#t=<token>. O token vem no fragmento (#), que não
 * é enviado ao servidor web nem ao Referer; é lido, apagado da barra de endereço e trocado por uma sessão presa à
 * consulta. Com a sessão aberta, o médico vai para a sala (ou, se a consulta já passou, só para a leitura).
 */
@Component({
  selector: 'app-acesso-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="pagina">
      @if (erro()) {
        <section class="card">
          <h1>Link inválido ou expirado</h1>
          <p>Este link só vale uma vez e por poucos minutos. Volte ao sistema de origem e peça para iniciar a consulta de novo.</p>
        </section>
      } @else {
        <p role="status">Abrindo a consulta…</p>
      }
    </main>
  `,
})
export class AcessoMedico {
  private readonly api = inject(Api);
  private readonly auth = inject(MedicoAuth);
  private readonly router = inject(Router);

  protected readonly erro = signal(false);

  constructor() {
    const token = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('t');
    history.replaceState(null, '', window.location.pathname); // o token sai da barra de endereço e do histórico
    if (!token) {
      this.erro.set(true);
      return;
    }
    this.api.trocarLink(token).subscribe({
      next: (s) => {
        this.auth.definirSessao(s.sessao, s.medicoNome);
        void this.router.navigate(s.modo === 'SALA' ? ['/medico/consulta', s.agendamentoId] : ['/medico/transcricao', s.consultaId]);
      },
      error: () => this.erro.set(true),
    });
  }
}
