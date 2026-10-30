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

/** Arquivo de áudio transitório de uma fonte (participante) da consulta. Só o registro; o áudio fica no disco. */
@Entity
@Table(name = "audio_transitorio")
public class AudioTransitorio {

	@Id
	private UUID id;

	@Column(name = "consulta_id", nullable = false)
	private UUID consultaId;

	@Column(nullable = false, length = 100)
	private String fonte;

	@Column(name = "endpoint_id", length = 64)
	private String endpointId;

	/** Caminho relativo ao diretório do sink: {consultaId}/{arquivo}. */
	@Column(nullable = false, length = 300)
	private String arquivo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AudioStatus status;

	@Column(name = "iniciado_em", nullable = false)
	private Instant iniciadoEm;

	@Column(name = "finalizado_em")
	private Instant finalizadoEm;

	@Column(nullable = false)
	private long amostras;

	protected AudioTransitorio() {
	}

	public AudioTransitorio(UUID consultaId, String fonte, String endpointId, String arquivo, Instant iniciadoEm) {
		this.id = UUID.randomUUID();
		this.consultaId = consultaId;
		this.fonte = fonte;
		this.endpointId = endpointId;
		this.arquivo = arquivo;
		this.status = AudioStatus.GRAVANDO;
		this.iniciadoEm = iniciadoEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public void completar(Instant agora, long amostras) {
		this.status = AudioStatus.COMPLETO;
		this.finalizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
		this.amostras = amostras;
	}

	/** O arquivo já foi apagado do disco; a linha fica como registro de que o áudio existiu (o momento vai na auditoria). */
	public void descartar(Instant agora) {
		this.status = AudioStatus.DESCARTADO;
		if (this.finalizadoEm == null) {
			this.finalizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getConsultaId() {
		return consultaId;
	}

	public String getFonte() {
		return fonte;
	}

	public String getEndpointId() {
		return endpointId;
	}

	public String getArquivo() {
		return arquivo;
	}

	public AudioStatus getStatus() {
		return status;
	}

	public Instant getIniciadoEm() {
		return iniciadoEm;
	}

	public Instant getFinalizadoEm() {
		return finalizadoEm;
	}

	public long getAmostras() {
		return amostras;
	}
}
