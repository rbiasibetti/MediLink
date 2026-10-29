package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.Nationalized;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agendamento")
public class Agendamento {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "medico_id", nullable = false)
	private Medico medico;

	@Nationalized
	@Column(name = "paciente_nome", nullable = false, length = 200)
	private String pacienteNome;

	/** HMAC-SHA256 do CPF em hex; o CPF em claro nunca é persistido. */
	@Column(name = "paciente_cpf_hmac", nullable = false, length = 64)
	private String pacienteCpfHmac;

	@Nationalized
	@Column(name = "paciente_email", length = 254)
	private String pacienteEmail;

	@Column(name = "data_hora", nullable = false)
	private Instant dataHora;

	@Column(name = "room_name", nullable = false, unique = true, length = 64)
	private String roomName;

	@Column(name = "cpf_validation_enabled", nullable = false)
	private boolean cpfValidationEnabled;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AgendamentoStatus status;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	protected Agendamento() {
	}

	public Agendamento(Medico medico, String pacienteNome, String pacienteCpfHmac, String pacienteEmail,
			Instant dataHora, String roomName, boolean cpfValidationEnabled, Instant criadoEm) {
		this.id = UUID.randomUUID();
		this.medico = medico;
		this.pacienteNome = pacienteNome;
		this.pacienteCpfHmac = pacienteCpfHmac;
		this.pacienteEmail = pacienteEmail;
		this.dataHora = dataHora;
		this.roomName = roomName;
		this.cpfValidationEnabled = cpfValidationEnabled;
		this.status = AgendamentoStatus.AGENDADO;
		this.criadoEm = criadoEm;
	}

	public UUID getId() {
		return id;
	}

	public Medico getMedico() {
		return medico;
	}

	public String getPacienteNome() {
		return pacienteNome;
	}

	public String getPacienteCpfHmac() {
		return pacienteCpfHmac;
	}

	public String getPacienteEmail() {
		return pacienteEmail;
	}

	public Instant getDataHora() {
		return dataHora;
	}

	public String getRoomName() {
		return roomName;
	}

	public boolean isCpfValidationEnabled() {
		return cpfValidationEnabled;
	}

	public AgendamentoStatus getStatus() {
		return status;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}
}
