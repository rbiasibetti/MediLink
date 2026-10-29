import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';

@Component({
  selector: 'app-login-medico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <main class="pagina">
      <section class="card">
        <h1>Acesso do médico</h1>
        <form (ngSubmit)="entrar()">
          <label for="usuario">E-mail</label>
          <input id="usuario" name="usuario" type="email" autocomplete="username" [(ngModel)]="usuario" required />
          <label for="senha">Senha</label>
          <input id="senha" name="senha" type="password" autocomplete="current-password" [(ngModel)]="senha" required />
          @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
          <button class="btn" type="submit" [disabled]="enviando() || !usuario() || !senha()">Entrar</button>
        </form>
        <p class="muted">Login provisório (HTTP Basic); o mecanismo definitivo é decisão em aberto.</p>
      </section>
    </main>
  `,
})
export class LoginMedico {
  private readonly api = inject(Api);
  private readonly auth = inject(MedicoAuth);
  private readonly router = inject(Router);

  protected readonly usuario = signal('');
  protected readonly senha = signal('');
  protected readonly erro = signal<string | null>(null);
  protected readonly enviando = signal(false);

  protected entrar(): void {
    this.enviando.set(true);
    this.erro.set(null);
    this.auth.definir(this.usuario(), this.senha());
    this.api.eu().subscribe({
      next: (eu) => {
        this.auth.confirmar(eu.nome);
        this.senha.set('');
        void this.router.navigate(['/medico/agendar']);
      },
      error: () => {
        this.auth.sair();
        this.enviando.set(false);
        this.erro.set('E-mail ou senha inválidos.');
      },
    });
  }
}
