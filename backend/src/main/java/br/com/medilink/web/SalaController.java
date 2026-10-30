package br.com.medilink.web;

import br.com.medilink.service.AcessoService;
import br.com.medilink.service.AcessoService.Info;
import br.com.medilink.service.EntradaTokenService.Emitido;
import br.com.medilink.service.SessaoJitsiService;
import br.com.medilink.web.AgendamentoDtos.SessaoResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Rotas públicas do paciente (liberadas no SecurityConfig). A proteção é o CPF + o link não adivinhável. */
@RestController
@RequestMapping("/api/salas/{roomName}")
class SalaController {

	/** O corpo é opcional: com a validação desativada o paciente não informa CPF. */
	record ValidarCpfRequest(String cpf) {

		@Override
		public String toString() {
			return "ValidarCpfRequest[]";
		}
	}

	record EntradaResponse(String tokenEntrada, Instant expiraEm, java.util.UUID consultaId) {
	}

	record TokenRequest(String tokenEntrada) {

		@Override
		public String toString() {
			return "TokenRequest[]";
		}
	}

	private final AcessoService acesso;
	private final SessaoJitsiService sessoes;

	SalaController(AcessoService acesso, SessaoJitsiService sessoes) {
		this.acesso = acesso;
		this.sessoes = sessoes;
	}

	@GetMapping("/acesso")
	Info acesso(@PathVariable String roomName) {
		return acesso.acesso(roomName);
	}

	@PostMapping("/validar-cpf")
	EntradaResponse validarCpf(@PathVariable String roomName,
			@RequestBody(required = false) ValidarCpfRequest req) {
		Emitido e = acesso.validar(roomName, req == null ? null : req.cpf());
		return new EntradaResponse(e.token(), e.expiraEm(), acesso.consultaDaSala(roomName));
	}

	/**
	 * Troca o token de entrada pelo JWT do Jitsi do paciente. Responde 202 (sem JWT) enquanto o médico não
	 * aceitar; o paciente repete a chamada até receber 200 com o JWT.
	 */
	@PostMapping("/token")
	ResponseEntity<SessaoResponse> token(@PathVariable String roomName, @RequestBody(required = false) TokenRequest req) {
		var s = sessoes.paraPaciente(roomName, req == null ? null : req.tokenEntrada());
		var corpo = new SessaoResponse(s.estado().name(), s.roomName(), s.consultaId(), s.jwt());
		return s.estado() == SessaoJitsiService.Estado.ADMITIDO
				? ResponseEntity.ok(corpo)
				: ResponseEntity.accepted().header("Retry-After", "3").body(corpo);
	}
}
