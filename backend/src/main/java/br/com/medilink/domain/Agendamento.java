package br.com.medilink.domain;

import br.com.medilink.crypto.CifraTextoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

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

	/** Cifrado em repouso (T16): o texto guardado é {@code enc:…}. */
	@Convert(converter = CifraTextoConverter.class)
	@Nationalized
	@Column(name = "paciente_nome", nullable = false, length = 1200)
	private String pacienteNome;

	/** HMAC-SHA256 do CPF em hex; o CPF em claro nunca é persistido. Nulo = sem CPF (validação desligada). */
	@Column(name = "paciente_cpf_hmac", length = 64)
	private String pacienteCpfHmac;

	@Convert(converter = CifraTextoConverter.class)
	@Nationalized
	@Column(name = "paciente_email", length = 1200)
	private String pacienteEmail;

	/** Sistema integrado que criou o agendamento (T16); nulo para os criados pelo médico no Angular. */
	@Column(name = "integrador_id")
	private UUID integradorId;

	/** Chave do agendamento no sistema de origem: torna a criação idempotente. */
	@Column(name = "referencia_externa", length = 100)
	private String referenciaExterna;

	/** Caminho e parâmetros do webhook (T19), cifrados: podem carregar segredo do cliente. */
	@Convert(converter = CifraTextoConverter.class)
	@Nationalized
	@Column(name = "webhook_destino")
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	private String webhookDestino;

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

	public void definirIntegracao(UUID integradorId, String referenciaExterna, String webhookDestino) {
		this.integradorId = integradorId;
		this.referenciaExterna = referenciaExterna;
		this.webhookDestino = webhookDestino;
	}

	public void alterarPaciente(String nome, String email, String cpfHmac, boolean validarCpf) {
		this.pacienteNome = nome;
		this.pacienteEmail = email;
		this.pacienteCpfHmac = cpfHmac;
		this.cpfValidationEnabled = validarCpf;
	}

	public void reagendar(Instant novaDataHora) {
		this.dataHora = novaDataHora;
	}

	public void alterarWebhookDestino(String destino) {
		this.webhookDestino = destino;
	}

	/** Cancelamento: o link deixa de funcionar (o acesso do paciente só vale para AGENDADO). */
	public void cancelar() {
		this.status = AgendamentoStatus.CANCELADO;
	}

	public UUID getIntegradorId() {
		return integradorId;
	}

	public String getReferenciaExterna() {
		return referenciaExterna;
	}

	public String getWebhookDestino() {
		return webhookDestino;
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
