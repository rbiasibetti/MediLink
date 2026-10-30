package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Link de uso único para o médico entrar na consulta (T17). Guarda só o hash do token. */
@Entity
@Table(name = "acesso_medico_link")
public class AcessoMedicoLink {

	public enum Modo { SALA, LEITURA }

	@Id
	private UUID id;

	@Column(name = "agendamento_id", nullable = false)
	private UUID agendamentoId;

	@Column(name = "consulta_id", nullable = false)
	private UUID consultaId;

	@Column(name = "medico_id", nullable = false)
	private UUID medicoId;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "hash_token", nullable = false, length = 64)
	private String hashToken;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Modo modo;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "expira_em", nullable = false)
	private Instant expiraEm;

	@Column(name = "usado_em")
	private Instant usadoEm;

	@Column(name = "revogado_em")
	private Instant revogadoEm;

	protected AcessoMedicoLink() {
	}

	public AcessoMedicoLink(UUID agendamentoId, UUID consultaId, UUID medicoId, String hashToken, Modo modo, Instant criadoEm, Instant expiraEm) {
		this.id = UUID.randomUUID();
		this.agendamentoId = agendamentoId;
		this.consultaId = consultaId;
		this.medicoId = medicoId;
		this.hashToken = hashToken;
		this.modo = modo;
		this.criadoEm = criadoEm.truncatedTo(ChronoUnit.MILLIS);
		this.expiraEm = expiraEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public void revogar(Instant agora) {
		this.revogadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getAgendamentoId() {
		return agendamentoId;
	}

	public UUID getConsultaId() {
		return consultaId;
	}

	public UUID getMedicoId() {
		return medicoId;
	}

	public Modo getModo() {
		return modo;
	}

	public Instant getExpiraEm() {
		return expiraEm;
	}

	public Instant getUsadoEm() {
		return usadoEm;
	}

	public Instant getRevogadoEm() {
		return revogadoEm;
	}
}
