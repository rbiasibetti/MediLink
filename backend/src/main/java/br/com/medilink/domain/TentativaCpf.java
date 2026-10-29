package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Uma tentativa falha de confirmar o CPF. Guarda apenas quando, nunca o valor informado. */
@Entity
@Table(name = "tentativa_cpf")
public class TentativaCpf {

	@Id
	private UUID id;

	@Column(name = "agendamento_id", nullable = false)
	private UUID agendamentoId;

	@Column(nullable = false)
	private Instant momento;

	protected TentativaCpf() {
	}

	public TentativaCpf(UUID agendamentoId, Instant momento) {
		this.id = UUID.randomUUID();
		this.agendamentoId = agendamentoId;
		this.momento = momento;
	}

	public UUID getId() {
		return id;
	}

	public UUID getAgendamentoId() {
		return agendamentoId;
	}

	public Instant getMomento() {
		return momento;
	}
}
