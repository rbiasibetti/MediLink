package br.com.medilink.web;

import br.com.medilink.domain.AgendamentoStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AgendamentoDtos {

	private AgendamentoDtos() {
	}

	/** O CPF nunca volta em nenhuma resposta nem aparece no toString. */
	public record CriarRequest(
			@NotBlank @Size(max = 200) String pacienteNome,
			@NotBlank @ValidCpf String pacienteCpf,
			@Email @Size(max = 254) String pacienteEmail,
			@NotNull @Future OffsetDateTime dataHora,
			Boolean cpfValidationEnabled) {

		@Override
		public String toString() {
			return "CriarRequest[pacienteNome=" + pacienteNome + ", dataHora=" + dataHora + "]";
		}
	}

	public record Resposta(UUID id, UUID consultaId, UUID medicoId, String pacienteNome, Instant dataHora,
			String roomName, String link, boolean cpfValidationEnabled, AgendamentoStatus status) {
	}

	/**
	 * O que o Angular passa ao IFrame do Jitsi: sala, JWT e a consulta (para aceite/encerramento).
	 * Para o paciente, {@code jwt} é nulo enquanto o estado for AGUARDANDO_MEDICO.
	 */
	public record SessaoResponse(String estado, String roomName, UUID consultaId, String jwt) {

		@Override
		public String toString() {
			return "SessaoResponse[estado=" + estado + ", roomName=" + roomName + "]";
		}
	}
}
