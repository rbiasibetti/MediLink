package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Liga o endpoint do Jitsi (o que o JVB informa ao sink) ao papel do participante na consulta. */
@Entity
@Table(name = "consulta_participante")
public class ConsultaParticipante {

	@Id
	private UUID id;

	@Column(name = "consulta_id", nullable = false)
	private UUID consultaId;

	@Column(name = "endpoint_id", nullable = false, length = 64)
	private String endpointId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PapelParticipante papel;

	@Column(name = "registrado_em", nullable = false)
	private Instant registradoEm;

	protected ConsultaParticipante() {
	}

	public ConsultaParticipante(UUID consultaId, String endpointId, PapelParticipante papel, Instant registradoEm) {
		this.id = UUID.randomUUID();
		this.consultaId = consultaId;
		this.endpointId = endpointId;
		this.papel = papel;
		this.registradoEm = registradoEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getConsultaId() {
		return consultaId;
	}

	public String getEndpointId() {
		return endpointId;
	}

	public PapelParticipante getPapel() {
		return papel;
	}

	public Instant getRegistradoEm() {
		return registradoEm;
	}
}
