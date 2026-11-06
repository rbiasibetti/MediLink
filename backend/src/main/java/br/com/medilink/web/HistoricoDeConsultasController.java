package br.com.medilink.web;

import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.service.HistoricoDeConsultasService;
import br.com.medilink.service.HistoricoDeConsultasService.Pagina;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** As consultas do médico autenticado, da mais recente para a mais antiga (T20). */
@RestController
@RequestMapping("/api/consultas")
class HistoricoDeConsultasController {

	private final HistoricoDeConsultasService historico;

	HistoricoDeConsultasController(HistoricoDeConsultasService historico) {
		this.historico = historico;
	}

	@GetMapping
	ResponseEntity<Pagina> listar(@RequestParam(required = false) ConsultaStatus status, @RequestParam(defaultValue = "0") int pagina,
			@RequestParam(defaultValue = "20") int tamanho, Principal medico) {
		return ResponseEntity.ok().header("Cache-Control", "no-store").body(historico.listar(medico.getName(), status, pagina, tamanho));
	}
}
