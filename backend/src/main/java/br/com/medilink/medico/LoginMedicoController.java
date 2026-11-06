package br.com.medilink.medico;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Login do médico por e-mail e senha (T20). Público; a resposta de falha é sempre a mesma. */
@RestController
@RequestMapping("/api/medico/login")
class LoginMedicoController {

	record Pedido(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String senha, @Size(max = 10) String codigo) {
		@Override
		public String toString() {
			return "Pedido[]";
		}
	}

	record Resposta(String etapa, String token, Instant expiraEm, String nome, String segredoTotp, String otpauthUri) {
		@Override
		public String toString() {
			return "Resposta[etapa=" + etapa + "]";
		}
	}

	private final LoginMedicoService login;

	LoginMedicoController(LoginMedicoService login) {
		this.login = login;
	}

	@PostMapping
	ResponseEntity<Resposta> entrar(@Valid @RequestBody Pedido pedido) {
		var r = login.entrar(pedido.email(), pedido.senha(), pedido.codigo());
		return ResponseEntity.ok().header("Cache-Control", "no-store")
				.body(new Resposta(r.etapa().name(), r.token(), r.expiraEm(), r.nome(), r.segredo(), r.otpauthUri()));
	}
}
