import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../core/api';
import { MedicoAuth } from '../core/medico-auth';

/**
 * Acesso do médico por e-mail e senha (T20). O código do autenticador só é pedido quando o administrador habilitou o TOTP
 * para aquele médico: no primeiro login a tela mostra o segredo para cadastrar no aplicativo e pede o código.
 */
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
          @if (segredo(); as s) {
            <p>Cadastre este segredo no seu aplicativo autenticador (Google Authenticator, Authy…) e informe o código gerado:</p>
            <p><code>{{ s }}</code></p>
            <p class="muted">Endereço do cadastro: <code>{{ uri() }}</code></p>
          }
          @if (pedirCodigo()) {
            <label for="codigo">Código do autenticador</label>
            <input id="codigo" name="codigo" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [(ngModel)]="codigo" />
          }
          @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
          <button class="btn" type="submit" [disabled]="enviando() || !usuario() || !senha()">Entrar</button>
        </form>
        <p class="muted">Não tem acesso? Peça o cadastro ao administrador da plataforma.</p>
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
  protected readonly codigo = signal('');
  protected readonly segredo = signal<string | null>(null);
  protected readonly uri = signal<string | null>(null);
  /** Aparece depois do enrolamento, ou quando o servidor recusa sem código (pode ser a senha ou o TOTP). */
  protected readonly pedirCodigo = signal(false);
  protected readonly erro = signal<string | null>(null);
  protected readonly enviando = signal(false);

  protected entrar(): void {
    this.enviando.set(true);
    this.erro.set(null);
    this.api.loginMedico(this.usuario().trim(), this.senha(), this.codigo().trim() || undefined).subscribe({
      next: (r) => {
        this.enviando.set(false);
        if (r.etapa === 'ENROLAR_TOTP') {
          this.segredo.set(r.segredoTotp ?? null);
          this.uri.set(r.otpauthUri ?? null);
          this.pedirCodigo.set(true);
          return;
        }
        this.auth.definirSessao(r.token ?? '', r.nome ?? '');
        this.senha.set('');
        this.codigo.set('');
        void this.router.navigate(['/medico/consultas']);
      },
      error: (e: HttpErrorResponse) => {
        this.enviando.set(false);
        this.codigo.set('');
        if (e.status === 429) {
          this.erro.set('Muitas tentativas. Aguarde alguns minutos.');
          return;
        }
        this.pedirCodigo.set(true); // não dá para saber se faltou o código: a resposta é a mesma de senha errada
        this.erro.set('Não foi possível entrar. Confira e-mail, senha e, se houver, o código do autenticador.');
      },
    });
  }
}
