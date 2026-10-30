package br.com.medilink.web;

import br.com.medilink.service.ParticipanteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * O cliente informa o próprio endpoint do Jitsi ao entrar na sala. Rota única com duas formas de autenticação:
 * o médico se autentica (HTTP Basic) e o paciente manda o token de entrada no corpo (rota pública no SecurityConfig).
 */
@RestController
class ParticipanteController {

	record ParticipanteRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String endpointId, String tokenEntrada) {

		@Override
		public String toString() {
			return "ParticipanteRequest[endpointId=" + endpointId + "]";
		}
	}

	private final ParticipanteService participantes;

	ParticipanteController(ParticipanteService participantes) {
		this.participantes = participantes;
	}

	@PostMapping("/api/consultas/{id}/participante")
	Map<String, String> registrar(@PathVariable UUID id, @Valid @RequestBody ParticipanteRequest req, Principal medico) {
		var papel = medico != null
				? participantes.registrarMedico(medico.getName(), id, req.endpointId())
				: participantes.registrarPaciente(id, req.tokenEntrada(), req.endpointId());
		return Map.of("papel", papel.name());
	}
}
