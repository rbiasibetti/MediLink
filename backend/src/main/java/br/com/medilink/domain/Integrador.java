package br.com.medilink.domain;

import br.com.medilink.crypto.CifraTextoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Sistema externo integrado à plataforma por API key (T16). Cada agendamento e médico "pertence" a um. */
@Entity
@Table(name = "integrador")
public class Integrador {

	@Id
	private UUID id;

	@Nationalized
	@Column(nullable = false, length = 200)
	private String nome;

	@Column(nullable = false)
	private boolean ativo;

	/** URL base HTTPS do webhook de devolução (T19). O destino final = base + caminho/parâmetros do agendamento. */
	@Column(name = "webhook_base_url", length = 500)
	private String webhookBaseUrl;

	/** Segredo da assinatura HMAC do webhook, cifrado em repouso. */
	@Convert(converter = CifraTextoConverter.class)
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(name = "webhook_segredo")
	private String webhookSegredo;

	@Column(name = "webhook_ativo", nullable = false)
	private boolean webhookAtivo;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	protected Integrador() {
	}

	public Integrador(String nome, Instant criadoEm) {
		this.id = UUID.randomUUID();
		this.nome = nome;
		this.ativo = true;
		this.criadoEm = criadoEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public void desativar() {
		this.ativo = false;
	}

	public void ativar() {
		this.ativo = true;
	}

	public void configurarWebhook(String baseUrl, String segredo, boolean ativo) {
		this.webhookBaseUrl = baseUrl;
		if (segredo != null) {
			this.webhookSegredo = segredo;
		}
		this.webhookAtivo = ativo;
	}

	public UUID getId() {
		return id;
	}

	public String getNome() {
		return nome;
	}

	public boolean isAtivo() {
		return ativo;
	}

	public String getWebhookBaseUrl() {
		return webhookBaseUrl;
	}

	public String getWebhookSegredo() {
		return webhookSegredo;
	}

	public boolean isWebhookAtivo() {
		return webhookAtivo;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}
}
