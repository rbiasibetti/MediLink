import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MedicoAuth } from './medico-auth';

export interface Acesso { medicoNome: string; dataHora: string; cpfObrigatorio: boolean }
export interface Entrada { tokenEntrada: string; expiraEm: string; consultaId: string }
export interface Termo { versao: string; texto: string }
export interface Situacao { consentimentoVigente: boolean; versaoDoTermo: string | null }

export type EstadoSessao = 'AGUARDANDO_MEDICO' | 'ADMITIDO';
/** `jwt` só vem preenchido quando o paciente foi admitido (e sempre para o médico). */
export interface Sessao { estado: EstadoSessao; roomName: string; consultaId: string; jwt: string | null }

export interface NovoAgendamento {
  pacienteNome: string;
  pacienteCpf: string;
  pacienteEmail?: string;
  dataHora: string;
  cpfValidationEnabled: boolean;
}
export interface AgendamentoCriado {
  id: string; consultaId: string; medicoId: string; pacienteNome: string; dataHora: string;
  roomName: string; link: string; cpfValidationEnabled: boolean; status: string;
}
export interface ConsultaDetalhe {
  id: string; roomName: string; status: string; inicioEm: string | null; fimEm: string | null;
  pacienteAguardando: boolean; consentimentoEm: string | null; consentimentoVersao: string | null;
  capturaAutorizada: boolean;
}

/** Cliente da API do MediLink. As rotas de paciente são públicas; as de médico levam o Basic provisório. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(MedicoAuth);

  // ---- paciente ----
  acesso(room: string): Observable<Acesso> {
    return this.http.get<Acesso>(`/api/salas/${encodeURIComponent(room)}/acesso`);
  }

  /** `cpf` nulo quando o médico desativou a validação. */
  validarCpf(room: string, cpf: string | null): Observable<Entrada> {
    return this.http.post<Entrada>(`/api/salas/${encodeURIComponent(room)}/validar-cpf`, cpf === null ? null : { cpf });
  }

  termo(): Observable<Termo> {
    return this.http.get<Termo>('/api/termo-consentimento');
  }

  consentir(consultaId: string, tokenEntrada: string, aceito: boolean, versaoTermo: string): Observable<Situacao> {
    return this.http.post<Situacao>(`/api/consultas/${consultaId}/consentimento`, { tokenEntrada, aceito, versaoTermo });
  }

  /** 202 (espera) e 200 (admitido) chegam como sucesso; o `estado` do corpo diz qual. */
  tokenPaciente(room: string, tokenEntrada: string): Observable<Sessao> {
    return this.http.post<Sessao>(`/api/salas/${encodeURIComponent(room)}/token`, { tokenEntrada });
  }

  /** Informa o endpoint do Jitsi do paciente; o papel vem do token de entrada, não do que o cliente diz. */
  registrarParticipantePaciente(consultaId: string, tokenEntrada: string, endpointId: string): Observable<{ papel: string }> {
    return this.http.post<{ papel: string }>(`/api/consultas/${consultaId}/participante`, { endpointId, tokenEntrada });
  }

  // ---- médico ----
  eu(): Observable<{ nome: string; email: string }> {
    return this.http.get<{ nome: string; email: string }>('/api/me', { headers: this.auth.headers() });
  }

  criarAgendamento(novo: NovoAgendamento): Observable<AgendamentoCriado> {
    return this.http.post<AgendamentoCriado>('/api/agendamentos', novo, { headers: this.auth.headers() });
  }

  enviarEmail(agendamentoId: string): Observable<{ enviadoEm: string }> {
    return this.http.post<{ enviadoEm: string }>(`/api/agendamentos/${agendamentoId}/enviar-email`, null, { headers: this.auth.headers() });
  }

  tokenMedico(agendamentoId: string): Observable<Sessao> {
    return this.http.post<Sessao>(`/api/agendamentos/${agendamentoId}/token-jitsi`, null, { headers: this.auth.headers() });
  }

  detalhe(consultaId: string): Observable<ConsultaDetalhe> {
    return this.http.get<ConsultaDetalhe>(`/api/consultas/${consultaId}`, { headers: this.auth.headers() });
  }

  aceitar(consultaId: string): Observable<ConsultaDetalhe> {
    return this.http.post<ConsultaDetalhe>(`/api/consultas/${consultaId}/aceite`, null, { headers: this.auth.headers() });
  }

  /** Informa o endpoint do Jitsi do médico (para rotular as falas na transcrição). */
  registrarParticipanteMedico(consultaId: string, endpointId: string): Observable<{ papel: string }> {
    return this.http.post<{ papel: string }>(`/api/consultas/${consultaId}/participante`, { endpointId }, { headers: this.auth.headers() });
  }

  encerrar(consultaId: string): Observable<ConsultaDetalhe> {
    return this.http.post<ConsultaDetalhe>(`/api/consultas/${consultaId}/encerrar`, null, { headers: this.auth.headers() });
  }
}
