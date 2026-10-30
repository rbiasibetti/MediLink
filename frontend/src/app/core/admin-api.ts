import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminAuth } from './admin-auth';

export interface LoginAdmin { etapa: 'OK' | 'ENROLAR_TOTP'; token?: string; expiraEm?: string; segredoTotp?: string; otpauthUri?: string }
export interface Incidente { consultaId: string; tipo: string; desde: string; erro: string | null }
export interface PorSistema { sistema: string; total: number; noPeriodo: number }
export interface Painel {
  geradoEm: string; periodoDias: number;
  agendamentos: { porStatus: Record<string, number>; criadosNoPeriodo: number; porSistema: PorSistema[] };
  consultas: { porStatus: Record<string, number>; encerradasNoPeriodo: number };
  transcricao: { jobsPorStatus: Record<string, number>; transcricoesGravadas: number; audioAindaNoDisco: number; audioMaisAntigoDesde: string | null };
  webhooks: Record<string, number>;
  incidentes: Incidente[];
}
export interface Integrador {
  id: string; nome: string; ativo: boolean; webhookBaseUrl: string | null; webhookAtivo: boolean; webhookTemSegredo: boolean; criadoEm: string;
}
export interface ChaveApi { id: string; nome: string; escopos: string; ativa: boolean; criadaEm: string; expiraEm: string | null; ultimoUsoEm: string | null; revogadaEm: string | null }
export interface Termo { id: string; versao: string; status: 'RASCUNHO' | 'VIGENTE' | 'ARQUIVADO'; texto: string; hash: string; criadoPor: string; criadoEm: string; publicadoEm: string | null }
export interface Entrega {
  id: string; evento: string; motivo: string | null; status: string; tentativas: number; ultimoStatusHttp: number | null; ultimoErro: string | null;
  criadoEm: string; proximaTentativaEm: string | null; entregueEm: string | null; consultaId: string; integradorId: string;
}

/** Cliente da API administrativa (T18). Todas as chamadas, menos o login, levam a sessão do administrador. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AdminAuth);
  private h() { return { headers: this.auth.headers() }; }

  login(email: string, senha: string, codigo?: string): Observable<LoginAdmin> {
    return this.http.post<LoginAdmin>('/api/admin/login', { email, senha, ...(codigo ? { codigo } : {}) });
  }

  painel(dias: number): Observable<Painel> { return this.http.get<Painel>(`/api/admin/painel?dias=${dias}`, this.h()); }
  reenviarJob(consultaId: string): Observable<void> { return this.http.post<void>(`/api/admin/jobs/${consultaId}/reenviar`, null, this.h()); }

  integradores(): Observable<Integrador[]> { return this.http.get<Integrador[]>('/api/admin/integradores', this.h()); }
  criarIntegrador(nome: string): Observable<Integrador> { return this.http.post<Integrador>('/api/admin/integradores', { nome }, this.h()); }
  alterarIntegrador(id: string, ativo: boolean): Observable<Integrador> { return this.http.patch<Integrador>(`/api/admin/integradores/${id}`, { ativo }, this.h()); }
  webhook(id: string, baseUrl: string | null, ativo: boolean, novoSegredo: boolean): Observable<{ integrador: Integrador; segredo: string | null }> {
    return this.http.put<{ integrador: Integrador; segredo: string | null }>(`/api/admin/integradores/${id}/webhook`, { baseUrl, ativo, novoSegredo }, this.h());
  }

  chaves(integradorId: string): Observable<ChaveApi[]> { return this.http.get<ChaveApi[]>(`/api/admin/integradores/${integradorId}/chaves`, this.h()); }
  criarChave(integradorId: string, nome: string, escopos: string[], diasDeValidade?: number): Observable<{ id: string; chave: string }> {
    return this.http.post<{ id: string; chave: string }>(`/api/admin/integradores/${integradorId}/chaves`, { nome, escopos, diasDeValidade }, this.h());
  }
  revogarChave(keyId: string): Observable<void> { return this.http.post<void>(`/api/admin/chaves/${keyId}/revogar`, null, this.h()); }

  termos(): Observable<Termo[]> { return this.http.get<Termo[]>('/api/admin/termos', this.h()); }
  criarTermo(texto: string): Observable<Termo> { return this.http.post<Termo>('/api/admin/termos', { texto }, this.h()); }
  editarTermo(id: string, texto: string): Observable<Termo> { return this.http.put<Termo>(`/api/admin/termos/${id}`, { texto }, this.h()); }
  publicarTermo(id: string): Observable<Termo> { return this.http.post<Termo>(`/api/admin/termos/${id}/publicar`, null, this.h()); }
  excluirTermo(id: string): Observable<void> { return this.http.delete<void>(`/api/admin/termos/${id}`, this.h()); }

  entregas(status?: string): Observable<Entrega[]> {
    return this.http.get<Entrega[]>(`/api/admin/webhooks/entregas${status ? `?status=${status}` : ''}`, this.h());
  }
  reenviarEntrega(id: string): Observable<void> { return this.http.post<void>(`/api/admin/webhooks/entregas/${id}/reenviar`, null, this.h()); }
}
