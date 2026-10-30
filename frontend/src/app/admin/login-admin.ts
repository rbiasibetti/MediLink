import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AdminApi } from '../core/admin-api';
import { AdminAuth } from '../core/admin-auth';

/** Login do administrador: senha e código do autenticador (TOTP). No primeiro acesso mostra o segredo para cadastrar o autenticador. */
@Component({
  selector: 'app-login-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <main class="pagina">
      <section class="card">
        <h1>Administração</h1>
        <form (ngSubmit)="entrar()">
          <label for="email">E-mail</label>
          <input id="email" name="email" type="email" autocomplete="username" [(ngModel)]="email" required />
          <label for="senha">Senha</label>
          <input id="senha" name="senha" type="password" autocomplete="current-password" [(ngModel)]="senha" required />
          @if (segredo()) {
            <div role="note">
              <p><strong>Primeiro acesso:</strong> cadastre este segredo no seu aplicativo autenticador (tipo de tempo, 6 dígitos) e informe o código gerado.</p>
              <p><code>{{ segredo() }}</code></p>
              <p class="muted">Ou use o endereço <code>{{ uri() }}</code> para gerar o QR Code.</p>
            </div>
          }
          @if (segredo() || precisaDeCodigo()) {
            <label for="codigo">Código do autenticador</label>
            <input id="codigo" name="codigo" inputmode="numeric" autocomplete="one-time-code" maxlength="6" [(ngModel)]="codigo" />
          }
          @if (erro()) { <p class="erro" role="alert">{{ erro() }}</p> }
          <button class="btn" type="submit" [disabled]="enviando() || !email() || !senha()">Entrar</button>
        </form>
      </section>
    </main>
  `,
})
export class LoginAdmin {
  private readonly api = inject(AdminApi);
  private readonly auth = inject(AdminAuth);
  private readonly router = inject(Router);

  protected readonly email = signal('');
  protected readonly senha = signal('');
  protected readonly codigo = signal('');
  protected readonly segredo = signal<string | null>(null);
  protected readonly uri = signal<string | null>(null);
  protected readonly precisaDeCodigo = signal(false);
  protected readonly erro = signal<string | null>(null);
  protected readonly enviando = signal(false);

  protected entrar(): void {
    this.enviando.set(true);
    this.erro.set(null);
    this.api.login(this.email(), this.senha(), this.codigo() || undefined).subscribe({
      next: (r) => {
        this.enviando.set(false);
        if (r.etapa === 'ENROLAR_TOTP') {
          this.segredo.set(r.segredoTotp ?? null);
          this.uri.set(r.otpauthUri ?? null);
          return;
        }
        this.auth.entrar(r.token!);
        this.senha.set('');
        this.codigo.set('');
        void this.router.navigate(['/admin/painel']);
      },
      error: (e: HttpErrorResponse) => {
        this.enviando.set(false);
        if (!this.segredo()) this.precisaDeCodigo.set(true);
        this.erro.set(e.status === 429 ? 'Muitas tentativas. Aguarde alguns minutos.' : 'Não foi possível autenticar. Confira e-mail, senha e código.');
      },
    });
  }
}
