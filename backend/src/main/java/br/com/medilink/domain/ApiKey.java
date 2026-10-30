package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** API key de um sistema integrado. Só o hash do segredo é guardado (T16). */
@Entity
@Table(name = "api_key")
public class ApiKey {

	@Id
	private UUID id;

	@Column(name = "integrador_id", nullable = false)
	private UUID integradorId;

	@Nationalized
	@Column(nullable = false, length = 100)
	private String nome;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "hash_segredo", nullable = false, length = 64)
	private String hashSegredo;

	/** Escopos separados por vírgula, ex.: {@code agendamento:escrever,agendamento:ler,sala:iniciar}. */
	@Column(nullable = false, length = 200)
	private String escopos;

	@Column(nullable = false)
	private boolean ativa;

	@Column(name = "criada_em", nullable = false)
	private Instant criadaEm;

	@Column(name = "expira_em")
	private Instant expiraEm;

	@Column(name = "ultimo_uso_em")
	private Instant ultimoUsoEm;

	@Column(name = "revogada_em")
	private Instant revogadaEm;

	protected ApiKey() {
	}

	public ApiKey(UUID id, UUID integradorId, String nome, String hashSegredo, String escopos, Instant criadaEm, Instant expiraEm) {
		this.id = id;
		this.integradorId = integradorId;
		this.nome = nome;
		this.hashSegredo = hashSegredo;
		this.escopos = escopos;
		this.ativa = true;
		this.criadaEm = criadaEm.truncatedTo(ChronoUnit.MILLIS);
		this.expiraEm = expiraEm == null ? null : expiraEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public boolean utilizavel(Instant agora) {
		return ativa && revogadaEm == null && (expiraEm == null || expiraEm.isAfter(agora));
	}

	public void revogar(Instant agora) {
		this.ativa = false;
		this.revogadaEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public void registrarUso(Instant agora) {
		this.ultimoUsoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegradorId() {
		return integradorId;
	}

	public String getNome() {
		return nome;
	}

	public String getHashSegredo() {
		return hashSegredo;
	}

	public String getEscopos() {
		return escopos;
	}

	public boolean isAtiva() {
		return ativa;
	}

	public Instant getCriadaEm() {
		return criadaEm;
	}

	public Instant getExpiraEm() {
		return expiraEm;
	}

	public Instant getUltimoUsoEm() {
		return ultimoUsoEm;
	}

	public Instant getRevogadaEm() {
		return revogadaEm;
	}
}
