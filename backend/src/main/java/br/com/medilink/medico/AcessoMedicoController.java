package br.com.medilink.medico;

import br.com.medilink.domain.AcessoMedicoLink.Modo;
import br.com.medilink.integracao.ApiKeyService.Autenticada;
import br.com.medilink.medico.AcessoMedicoService.LinkEmitido;
import br.com.medilink.medico.AcessoMedicoService.Troca;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Pedido do link pelo sistema integrado (API key) e troca do link pela sessão (navegador do médico, sem login). */
@RestController
class AcessoMedicoController {

	record IniciarSalaRequest(@NotBlank @Size(max = 200) String nomeMedico,
			@NotBlank @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9._:@/\\-]+") String chaveMedico) {
	}

	record IniciarSalaResposta(String linkMedico, Instant expiraEm, Modo modo) {
		@Override
		public String toString() {
			return "IniciarSalaResposta[modo=" + modo + "]";
		}
	}

	record TrocaRequest(@NotBlank @Size(max = 100) String token) {
		@Override
		public String toString() {
			return "TrocaRequest[]";
		}
	}

	record SessaoResposta(String sessao, Instant expiraEm, UUID agendamentoId, UUID consultaId, Modo modo, String medicoNome) {
		@Override
		public String toString() {
			return "SessaoResposta[modo=" + modo + "]";
		}
	}

	private final AcessoMedicoService service;

	AcessoMedicoController(AcessoMedicoService service) {
		this.service = service;
	}

	/** Iniciar a sala: devolve um link de uso único, nunca o JWT do Jitsi. Pode ser chamado de novo; só o último link vale. */
	@PostMapping("/api/integracao/v1/agendamentos/{chave}/acesso-medico")
	IniciarSalaResposta iniciarSala(@PathVariable UUID chave, @Valid @RequestBody IniciarSalaRequest req, @AuthenticationPrincipal Autenticada a) {
		LinkEmitido l = service.emitir(a, chave, req.chaveMedico().strip(), req.nomeMedico().strip());
		return new IniciarSalaResposta(l.link(), l.expiraEm(), l.modo());
	}

	/** Pública: quem tem o token (uso único, poucos minutos) recebe a sessão da consulta. */
	@PostMapping("/api/medico/sessao")
	SessaoResposta trocar(@Valid @RequestBody TrocaRequest req) {
		Troca t = service.trocar(req.token());
		return new SessaoResposta(t.sessao(), t.expiraEm(), t.agendamentoId(), t.consultaId(), t.modo(), t.medicoNome());
	}
}
