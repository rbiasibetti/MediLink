import { Injectable, computed, signal } from '@angular/core';

/** Sessão do administrador (T18). Só em memória: recarregar a página ou passar dos 30 min pede senha e código de novo. */
@Injectable({ providedIn: 'root' })
export class AdminAuth {
  private readonly token = signal<string | null>(null);
  readonly logado = computed(() => this.token() !== null);

  entrar(token: string): void {
    this.token.set(token);
  }

  sair(): void {
    this.token.set(null);
  }

  headers(): Record<string, string> {
    const t = this.token();
    return t ? { Authorization: `Bearer ${t}` } : {};
  }
}
