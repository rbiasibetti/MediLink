import { Injectable, computed, signal } from '@angular/core';

/**
 * Credenciais do médico para o HTTP Basic provisório do backend (docs/plan.md, decisão aberta de login).
 * Ficam só em memória: recarregar a página pede o login de novo. Nunca vão para storage nem para a URL.
 */
@Injectable({ providedIn: 'root' })
export class MedicoAuth {
  private readonly basic = signal<string | null>(null);
  readonly nome = signal<string | null>(null);
  readonly logado = computed(() => this.basic() !== null);

  definir(usuario: string, senha: string): void {
    const bytes = new TextEncoder().encode(`${usuario}:${senha}`);
    this.basic.set(btoa(String.fromCharCode(...bytes)));
  }

  confirmar(nome: string): void {
    this.nome.set(nome);
  }

  sair(): void {
    this.basic.set(null);
    this.nome.set(null);
  }

  /** Cabeçalhos das chamadas de médico. */
  headers(): Record<string, string> {
    const b = this.basic();
    return b ? { Authorization: `Basic ${b}` } : {};
  }
}
