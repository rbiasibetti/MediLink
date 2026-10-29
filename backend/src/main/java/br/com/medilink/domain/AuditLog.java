package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Nationalized;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {

	@Id
	private UUID id;

	@Nationalized
	@Column(nullable = false, length = 254)
	private String ator;

	@Column(nullable = false, length = 60)
	private String acao;

	@Column(nullable = false, length = 200)
	private String recurso;

	@Column(nullable = false)
	private Instant momento;

	protected AuditLog() {
	}

	public AuditLog(String ator, String acao, String recurso, Instant momento) {
		this.id = UUID.randomUUID();
		this.ator = ator;
		this.acao = acao;
		this.recurso = recurso;
		this.momento = momento;
	}

	public UUID getId() {
		return id;
	}

	public String getAtor() {
		return ator;
	}

	public String getAcao() {
		return acao;
	}

	public String getRecurso() {
		return recurso;
	}

	public Instant getMomento() {
		return momento;
	}
}
