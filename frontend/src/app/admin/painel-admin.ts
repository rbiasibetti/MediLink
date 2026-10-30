import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AdminAuth } from '../core/admin-auth';
import { ResumoAdmin } from './resumo-admin';
import { SistemasAdmin } from './sistemas-admin';
import { TermoAdmin } from './termo-admin';
import { WebhooksAdmin } from './webhooks-admin';

type Aba = 'resumo' | 'sistemas' | 'termo' | 'webhooks';

/** Área administrativa (T18): andamento, sistemas e keys, termo de consentimento e webhooks. A sessão dura 30 min e só vive em memória. */
@Component({
  selector: 'app-painel-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ResumoAdmin, SistemasAdmin, TermoAdmin, WebhooksAdmin],
  template: `
    <main class="pagina larga">
      <header class="barra">
        <nav aria-label="Seções da administração" class="acoes">
          @for (a of abas; track a.id) {
            <button class="btn" type="button" [class.secundario]="aba() !== a.id" [attr.aria-current]="aba() === a.id ? 'page' : null" (click)="aba.set(a.id)">{{ a.rotulo }}</button>
          }
        </nav>
        <button class="link" type="button" (click)="sair()">Sair</button>
      </header>
      @switch (aba()) {
        @case ('resumo') { <app-resumo-admin /> }
        @case ('sistemas') { <app-sistemas-admin /> }
        @case ('termo') { <app-termo-admin /> }
        @case ('webhooks') { <app-webhooks-admin /> }
      }
    </main>
  `,
})
export class PainelAdmin {
  private readonly auth = inject(AdminAuth);
  private readonly router = inject(Router);
  protected readonly aba = signal<Aba>('resumo');
  protected readonly abas: { id: Aba; rotulo: string }[] = [
    { id: 'resumo', rotulo: 'Andamento' }, { id: 'sistemas', rotulo: 'Sistemas e keys' },
    { id: 'termo', rotulo: 'Termo de consentimento' }, { id: 'webhooks', rotulo: 'Webhooks' },
  ];

  protected sair(): void {
    this.auth.sair();
    void this.router.navigate(['/admin']);
  }
}
