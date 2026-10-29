package br.com.medilink.web;

import br.com.medilink.service.ConsultaService;
import br.com.medilink.service.ConsultaService.Detalhe;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

/** Lado do médico na consulta. Exige médico autenticado e dono do agendamento. */
@RestController
@RequestMapping("/api/consultas/{id}")
class ConsultaController {

	private final ConsultaService consultas;

	ConsultaController(ConsultaService consultas) {
		this.consultas = consultas;
	}

	/** O Angular do médico consulta isto para saber se há paciente aguardando. */
	@GetMapping
	Detalhe detalhe(@PathVariable UUID id, Principal medico) {
		return consultas.detalhe(medico.getName(), id);
	}

	@PostMapping("/aceite")
	Detalhe aceitar(@PathVariable UUID id, Principal medico) {
		return consultas.aceitar(medico.getName(), id);
	}

	/** Chamado pelo Angular do médico quando ele sai da sala (videoConferenceLeft). */
	@PostMapping("/encerrar")
	Detalhe encerrar(@PathVariable UUID id, Principal medico) {
		return consultas.encerrar(medico.getName(), id);
	}
}
