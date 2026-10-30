package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "consulta")
public class Consulta {

	@Id
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "agendamento_id", nullable = false, unique = true)
	private Agendamento agendamento;

	@Column(name = "inicio_em")
	private Instant inicioEm;

	@Column(name = "fim_em")
	private Instant fimEm;

	@Column(name = "consentimento_em")
	private Instant consentimentoEm;

	@Column(name = "consentimento_versao", length = 20)
	private String consentimentoVersao;

	/** SHA-256 do texto do termo que o paciente aceitou: prova do que foi exibido, mesmo que o termo evolua. */
	@org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
	@Column(name = "consentimento_hash", length = 64)
	private String consentimentoHash;

	/** Último sinal de vida do médico (a tela dele consulta o estado a cada poucos segundos). */
	@Column(name = "ultima_presenca_medico_em")
	private Instant ultimaPresencaMedicoEm;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ConsultaStatus status;

	protected Consulta() {
	}

	public Consulta(Agendamento agendamento) {
		this.id = UUID.randomUUID();
		this.agendamento = agendamento;
		this.status = ConsultaStatus.CRIADA;
	}

	public UUID getId() {
		return id;
	}

	public Agendamento getAgendamento() {
		return agendamento;
	}

	public Instant getInicioEm() {
		return inicioEm;
	}

	public Instant getFimEm() {
		return fimEm;
	}

	public Instant getConsentimentoEm() {
		return consentimentoEm;
	}

	public String getConsentimentoVersao() {
		return consentimentoVersao;
	}

	public ConsultaStatus getStatus() {
		return status;
	}

	/** Consentimento ainda pode ser dado ou retirado: antes de a consulta terminar. */
	public boolean aceitaConsentimento() {
		return status == ConsultaStatus.CRIADA || status == ConsultaStatus.AGUARDANDO_PACIENTE
				|| status == ConsultaStatus.EM_ANDAMENTO;
	}

	public void registrarConsentimento(Instant agora, String versaoDoTermo, String hashDoTermo) {
		this.consentimentoEm = agora.truncatedTo(ChronoUnit.MILLIS);
		this.consentimentoVersao = versaoDoTermo;
		this.consentimentoHash = hashDoTermo;
	}

	public String getConsentimentoHash() {
		return consentimentoHash;
	}

	/** Retirada do consentimento (ou recusa após ter consentido): a captura deixa de ser autorizada. */
	public boolean revogarConsentimento() {
		boolean tinha = consentimentoEm != null;
		this.consentimentoEm = null;
		this.consentimentoVersao = null;
		this.consentimentoHash = null;
		return tinha;
	}

	/**
	 * Portão da captura de áudio (RF-08): a consulta precisa estar em andamento (paciente admitido) e ter
	 * consentimento vigente. O audio sink (T11) só grava quando isto é verdadeiro.
	 */
	public boolean capturaAutorizada() {
		return status == ConsultaStatus.EM_ANDAMENTO && consentimentoEm != null;
	}

	/** O paciente passou pela porta do CPF e aguarda o médico. Só a primeira chegada muda o estado. */
	public boolean registrarChegadaDoPaciente() {
		if (status != ConsultaStatus.CRIADA) {
			return false;
		}
		status = ConsultaStatus.AGUARDANDO_PACIENTE;
		return true;
	}

	/** Fim da consulta (feito pelo médico/host). Só uma consulta em andamento pode terminar. */
	public void encerrar(Instant agora) {
		if (status != ConsultaStatus.EM_ANDAMENTO) {
			throw new IllegalStateException("Consulta não está em andamento: " + status);
		}
		status = ConsultaStatus.ENCERRADA;
		fimEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	/** Aceite do médico: a consulta começa agora. Só vale com o paciente aguardando. */
	public void admitirPaciente(Instant agora) {
		if (status != ConsultaStatus.AGUARDANDO_PACIENTE) {
			throw new IllegalStateException("Consulta não está aguardando o aceite: " + status);
		}
		status = ConsultaStatus.EM_ANDAMENTO;
		inicioEm = agora.truncatedTo(ChronoUnit.MILLIS);
		ultimaPresencaMedicoEm = inicioEm;
	}

	public void registrarPresencaDoMedico(Instant agora) {
		this.ultimaPresencaMedicoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	/** Reabre uma consulta encerrada dentro da tolerância: volta a andamento, sem fim, e o médico está presente. */
	public void reabrir(Instant agora) {
		if (status != ConsultaStatus.ENCERRADA) {
			throw new IllegalStateException("Consulta não está encerrada: " + status);
		}
		status = ConsultaStatus.EM_ANDAMENTO;
		fimEm = null;
		ultimaPresencaMedicoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public Instant getUltimaPresencaMedicoEm() {
		return ultimaPresencaMedicoEm;
	}
}
