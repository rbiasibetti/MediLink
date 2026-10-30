package br.com.medilink.web;

import br.com.medilink.service.ConsultaService;
import br.com.medilink.service.ConsultaService.Detalhe;
import br.com.medilink.transcricao.LeituraDeTranscricaoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/** Lado do médico na consulta. Exige médico autenticado e dono do agendamento. */
@RestController
@RequestMapping("/api/consultas/{id}")
class ConsultaController {

	private final ConsultaService consultas;
	private final LeituraDeTranscricaoService leitura;

	ConsultaController(ConsultaService consultas, LeituraDeTranscricaoService leitura) {
		this.consultas = consultas;
		this.leitura = leitura;
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

	/** 200 com a transcrição; 202 enquanto o worker ainda processa. Dado sensível: só o médico dono lê. */
	@GetMapping("/transcricao")
	ResponseEntity<?> transcricao(@PathVariable UUID id, Principal medico) {
		return leitura.obter(medico.getName(), id)
				.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.accepted().body(Map.of("status", "EM_PROCESSAMENTO")));
	}

	/** Chamado pelo Angular do médico quando ele sai da sala (videoConferenceLeft). */
	@PostMapping("/encerrar")
	Detalhe encerrar(@PathVariable UUID id, Principal medico) {
		return consultas.encerrar(medico.getName(), id);
	}
}
