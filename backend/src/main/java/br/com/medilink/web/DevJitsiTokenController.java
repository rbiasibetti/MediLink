package br.com.medilink.web;

import br.com.medilink.service.JitsiTokenService;
import br.com.medilink.service.JitsiTokenService.Participante;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/** Só no perfil "dev": gera token para testar o Jitsi na mão (T02). O fluxo real é T07. */
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
class DevJitsiTokenController {

	record Pedido(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String room, boolean moderador) {
	}

	private final JitsiTokenService tokens;

	DevJitsiTokenController(JitsiTokenService tokens) {
		this.tokens = tokens;
	}

	@PostMapping("/jitsi-token")
	Map<String, String> token(@Valid @RequestBody Pedido pedido, Principal medico) {
		var quem = new Participante(UUID.randomUUID().toString(),
				pedido.moderador() ? medico.getName() : "Paciente (dev)", null, pedido.moderador());
		return Map.of("jwt", tokens.emitir(pedido.room(), quem));
	}
}
