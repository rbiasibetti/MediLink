import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterOutlet } from '@angular/router';
import { MedicoAuth } from './core/medico-auth';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: `
    <header class="topo">
      <span class="marca">MediLink</span>
      @if (auth.logado()) {
        <span class="muted">{{ auth.nome() }}</span>
        <button class="link" type="button" (click)="sair()">Sair</button>
      }
    </header>
    <router-outlet />
  `,
})
export class App {
  protected readonly auth = inject(MedicoAuth);
  private readonly router = inject(Router);

  protected sair(): void {
    this.auth.sair();
    void this.router.navigate(['/medico']);
  }
}
