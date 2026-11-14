import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/**
 * Casca das telas do médico (estilo SB Admin 2): barra lateral de navegação à esquerda e o conteúdo da tela à
 * direita. A marca, o nome e o "Sair" ficam na barra superior global (ver app.ts). Use envolvendo o conteúdo:
 * `<app-medico-layout><section class="painel-card">…</section></app-medico-layout>`.
 */
@Component({
  selector: 'app-medico-layout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  template: `
    <div class="app-layout">
      <aside class="sidebar">
        <nav aria-label="Navegação do médico">
          <a routerLink="/medico/consultas" routerLinkActive="ativo"><i class="bi bi-calendar2-week" aria-hidden="true"></i> Minhas consultas</a>
          <a routerLink="/medico/agendar" routerLinkActive="ativo"><i class="bi bi-plus-circle" aria-hidden="true"></i> Agendar consulta</a>
        </nav>
      </aside>
      <main class="app-content">
        <ng-content />
      </main>
    </div>
  `,
})
export class MedicoLayout {}
