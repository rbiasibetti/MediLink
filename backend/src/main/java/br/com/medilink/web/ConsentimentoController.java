package br.com.medilink.web;

import br.com.medilink.service.ConsentimentoService;
import br.com.medilink.service.ConsentimentoService.Situacao;
import br.com.medilink.service.TermoConsentimento;
import br.com.medilink.service.TermoConsentimento.Termo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Rotas públicas do paciente (liberadas no SecurityConfig): o termo e o registro do consentimento. */
@RestController
class ConsentimentoController {

	record ConsentimentoRequest(String tokenEntrada, boolean aceito, String versaoTermo) {

		@Override
		public String toString() {
			return "ConsentimentoRequest[aceito=" + aceito + ", versaoTermo=" + versaoTermo + "]";
		}
	}

	private final ConsentimentoService consentimento;
	private final TermoConsentimento termo;

	ConsentimentoController(ConsentimentoService consentimento, TermoConsentimento termo) {
		this.consentimento = consentimento;
		this.termo = termo;
	}

	@GetMapping("/api/termo-consentimento")
	Termo termo() {
		return termo.atual();
	}

	@PostMapping("/api/consultas/{id}/consentimento")
	Situacao consentir(@PathVariable UUID id, @RequestBody ConsentimentoRequest req) {
		return consentimento.registrar(id, req.tokenEntrada(), req.aceito(), req.versaoTermo());
	}
}
