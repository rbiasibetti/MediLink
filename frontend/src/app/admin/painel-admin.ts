import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AdminAuth } from '../core/admin-auth';
import { MedicosAdmin } from './medicos-admin';
import { ResumoAdmin } from './resumo-admin';
import { SistemasAdmin } from './sistemas-admin';
import { SmtpAdmin } from './smtp-admin';
import { TermoAdmin } from './termo-admin';
import { WebhooksAdmin } from './webhooks-admin';

type Aba = 'resumo' | 'medicos' | 'sistemas' | 'termo' | 'webhooks' | 'smtp';

/** Área administrativa (T18): andamento, médicos, sistemas e keys, termo de consentimento e webhooks. A sessão dura 30 min e só vive em memória. */
@Component({
  selector: 'app-painel-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ResumoAdmin, MedicosAdmin, SistemasAdmin, TermoAdmin, WebhooksAdmin, SmtpAdmin],
  template: `
    <div class="app-layout">
      <aside class="sidebar">
        <nav aria-label="Seções da administração">
          @for (a of abas; track a.id) {
            <button type="button" [class.ativo]="aba() === a.id" [attr.aria-current]="aba() === a.id ? 'page' : null" (click)="aba.set(a.id)">
              <i class="bi {{ a.icone }}" aria-hidden="true"></i> {{ a.rotulo }}
            </button>
          }
          <button type="button" class="sair" (click)="sair()"><i class="bi bi-box-arrow-right" aria-hidden="true"></i> Sair</button>
        </nav>
      </aside>
      <main class="app-content">
        @switch (aba()) {
          @case ('resumo') { <app-resumo-admin /> }
          @case ('medicos') { <app-medicos-admin /> }
          @case ('sistemas') { <app-sistemas-admin /> }
          @case ('termo') { <app-termo-admin /> }
          @case ('webhooks') { <app-webhooks-admin /> }
          @case ('smtp') { <app-smtp-admin /> }
        }
      </main>
    </div>
  `,
})
export class PainelAdmin {
  private readonly auth = inject(AdminAuth);
  private readonly router = inject(Router);
  protected readonly aba = signal<Aba>('resumo');
  protected readonly abas: { id: Aba; rotulo: string; icone: string }[] = [
    { id: 'resumo', rotulo: 'Andamento', icone: 'bi-speedometer2' },
    { id: 'medicos', rotulo: 'Médicos', icone: 'bi-person-badge' },
    { id: 'sistemas', rotulo: 'Sistemas e keys', icone: 'bi-plug' },
    { id: 'termo', rotulo: 'Termo de consentimento', icone: 'bi-file-earmark-text' },
    { id: 'webhooks', rotulo: 'Webhooks', icone: 'bi-arrow-repeat' },
    { id: 'smtp', rotulo: 'Envio de e-mail', icone: 'bi-envelope-at' },
  ];

  protected sair(): void {
    this.auth.sair();
    void this.router.navigate(['/admin']);
  }
}
