import { Injectable, computed, signal } from '@angular/core';

const CHAVE_SESSAO = 'medilink.sessao-medico';

/**
 * Credenciais do médico. Dois jeitos de entrar:
 * - HTTP Basic provisório (login com e-mail e senha), só em memória: recarregar a página pede o login de novo;
 * - sessão aberta por um link de uso único (T17): um token preso a UMA consulta, guardado em `sessionStorage` da aba
 *   para o médico poder recarregar a página ou voltar depois de uma queda. Fechar a aba descarta a sessão (aí vale um
 *   link novo, pedido pelo sistema de origem). Nunca vai para a URL.
 */
@Injectable({ providedIn: 'root' })
export class MedicoAuth {
  private readonly basic = signal<string | null>(null);
  private readonly sessao = signal<string | null>(lerSessao());
  readonly nome = signal<string | null>(null);
  readonly logado = computed(() => this.basic() !== null || this.sessao() !== null);

  definir(usuario: string, senha: string): void {
    const bytes = new TextEncoder().encode(`${usuario}:${senha}`);
    this.basic.set(btoa(String.fromCharCode(...bytes)));
  }

  /** Sessão aberta pelo link de uso único. */
  definirSessao(token: string, nome: string): void {
    this.sessao.set(token);
    this.nome.set(nome);
    try {
      sessionStorage.setItem(CHAVE_SESSAO, token);
    } catch {
      // sem storage (modo privado): a sessão vale só enquanto a página não recarregar
    }
  }

  confirmar(nome: string): void {
    this.nome.set(nome);
  }

  sair(): void {
    this.basic.set(null);
    this.sessao.set(null);
    this.nome.set(null);
    try {
      sessionStorage.removeItem(CHAVE_SESSAO);
    } catch {
      // ignorado
    }
  }

  /** Cabeçalhos das chamadas de médico: a sessão por link tem prioridade sobre o Basic. */
  headers(): Record<string, string> {
    const s = this.sessao();
    if (s) return { Authorization: `Bearer ${s}` };
    const b = this.basic();
    return b ? { Authorization: `Basic ${b}` } : {};
  }
}

function lerSessao(): string | null {
  try {
    return sessionStorage.getItem(CHAVE_SESSAO);
  } catch {
    return null;
  }
}
