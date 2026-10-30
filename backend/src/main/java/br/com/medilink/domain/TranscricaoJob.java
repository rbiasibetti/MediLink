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

/** Estado do envio do job de transcrição de uma consulta à fila. Um por consulta. */
@Entity
@Table(name = "transcricao_job")
public class TranscricaoJob {

	@Id
	private UUID id;

	@Column(name = "consulta_id", nullable = false, unique = true)
	private UUID consultaId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private JobStatus status;

	@Column(nullable = false)
	private int tentativas;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "atualizado_em", nullable = false)
	private Instant atualizadoEm;

	/** Só o tipo da exceção, nunca mensagem (poderia carregar conteúdo). */
	@Column(name = "ultimo_erro", length = 200)
	private String ultimoErro;

	protected TranscricaoJob() {
	}

	public TranscricaoJob(UUID consultaId, Instant agora) {
		this.id = UUID.randomUUID();
		this.consultaId = consultaId;
		this.status = JobStatus.PENDENTE;
		this.criadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
		this.atualizadoEm = this.criadoEm;
	}

	public void marcar(JobStatus novo, Instant agora) {
		this.status = novo;
		this.atualizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public void registrarFalha(String tipoDoErro, Instant agora) {
		this.tentativas++;
		this.ultimoErro = tipoDoErro.length() > 200 ? tipoDoErro.substring(0, 200) : tipoDoErro;
		this.atualizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	/** O worker respondeu que não conseguiu transcrever (só o tipo do erro, nunca mensagem). */
	public void falhouNoWorker(String tipoDoErro, Instant agora) {
		this.status = JobStatus.FALHA_NO_WORKER;
		this.ultimoErro = tipoDoErro.length() > 200 ? tipoDoErro.substring(0, 200) : tipoDoErro;
		this.atualizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getConsultaId() {
		return consultaId;
	}

	public JobStatus getStatus() {
		return status;
	}

	public int getTentativas() {
		return tentativas;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}

	public Instant getAtualizadoEm() {
		return atualizadoEm;
	}

	public String getUltimoErro() {
		return ultimoErro;
	}
}
