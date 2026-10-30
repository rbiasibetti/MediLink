package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Uma versão do termo de consentimento. Depois de publicada o texto não muda mais (T18). */
@Entity
@Table(name = "termo_consentimento")
public class TermoVersao {

	public enum Status { RASCUNHO, VIGENTE, ARQUIVADO }

	@Id
	private UUID id;

	@Column(nullable = false, length = 20)
	private String versao;

	@Nationalized
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(nullable = false, length = org.hibernate.Length.LONG32)
	private String texto;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "hash_texto", nullable = false, length = 64)
	private String hashTexto;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status;

	@Nationalized
	@Column(name = "criado_por", nullable = false, length = 254)
	private String criadoPor;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "publicado_em")
	private Instant publicadoEm;

	protected TermoVersao() {
	}

	public TermoVersao(String versao, String texto, String hashTexto, Status status, String criadoPor, Instant criadoEm) {
		this.id = UUID.randomUUID();
		this.versao = versao;
		this.texto = texto;
		this.hashTexto = hashTexto;
		this.status = status;
		this.criadoPor = criadoPor;
		this.criadoEm = criadoEm.truncatedTo(ChronoUnit.MILLIS);
		if (status == Status.VIGENTE) {
			this.publicadoEm = this.criadoEm;
		}
	}

	/** Só rascunho muda de texto. */
	public void reescrever(String novoTexto, String novoHash) {
		if (status != Status.RASCUNHO) {
			throw new IllegalStateException("Termo publicado é imutável");
		}
		this.texto = novoTexto;
		this.hashTexto = novoHash;
	}

	public void publicar(Instant agora) {
		if (status != Status.RASCUNHO) {
			throw new IllegalStateException("Só rascunho pode ser publicado");
		}
		this.status = Status.VIGENTE;
		this.publicadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public void arquivar() {
		if (status != Status.VIGENTE) {
			throw new IllegalStateException("Só o termo vigente pode ser arquivado");
		}
		this.status = Status.ARQUIVADO;
	}

	public UUID getId() {
		return id;
	}

	public String getVersao() {
		return versao;
	}

	public String getTexto() {
		return texto;
	}

	public String getHashTexto() {
		return hashTexto;
	}

	public Status getStatus() {
		return status;
	}

	public String getCriadoPor() {
		return criadoPor;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}

	public Instant getPublicadoEm() {
		return publicadoEm;
	}
}
