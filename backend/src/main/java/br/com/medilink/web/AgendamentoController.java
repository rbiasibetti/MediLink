package br.com.medilink.web;

import br.com.medilink.service.AgendamentoService;
import br.com.medilink.service.AgendamentoService.Criado;
import br.com.medilink.service.EmailService;
import br.com.medilink.service.SessaoJitsiService;
import br.com.medilink.web.AgendamentoDtos.CriarRequest;
import br.com.medilink.web.AgendamentoDtos.Resposta;
import br.com.medilink.web.AgendamentoDtos.SessaoResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agendamentos")
class AgendamentoController {

	private final AgendamentoService service;
	private final EmailService email;
	private final SessaoJitsiService sessoes;

	AgendamentoController(AgendamentoService service, EmailService email, SessaoJitsiService sessoes) {
		this.service = service;
		this.email = email;
		this.sessoes = sessoes;
	}

	@PostMapping
	ResponseEntity<Resposta> criar(@Valid @RequestBody CriarRequest req, Principal medico) {
		Criado criado = service.criar(medico.getName(), new AgendamentoService.Novo(
				req.pacienteNome(), req.pacienteCpf(), req.pacienteEmail(), req.dataHora().toInstant(),
				req.cpfValidationEnabled() == null || req.cpfValidationEnabled()));
		return ResponseEntity.created(URI.create("/api/agendamentos/" + criado.agendamento().getId()))
				.body(resposta(criado));
	}

	@GetMapping("/{id}")
	Resposta buscar(@PathVariable UUID id, Principal medico) {
		return resposta(service.buscar(medico.getName(), id));
	}

	@PostMapping("/{id}/enviar-email")
	Map<String, Instant> enviarEmail(@PathVariable UUID id, Principal medico) {
		return Map.of("enviadoEm", email.enviarLink(medico.getName(), id));
	}

	/** Credencial do médico (host) para a sala do agendamento. */
	@PostMapping("/{id}/token-jitsi")
	SessaoResponse tokenJitsi(@PathVariable UUID id, Principal medico) {
		var s = sessoes.paraMedico(medico.getName(), id);
		return new SessaoResponse(s.estado().name(), s.roomName(), s.consultaId(), s.jwt());
	}

	private static Resposta resposta(Criado c) {
		var a = c.agendamento();
		return new Resposta(a.getId(), c.consulta().getId(), a.getMedico().getId(), a.getPacienteNome(),
				a.getDataHora(), a.getRoomName(), c.link(), a.isCpfValidationEnabled(), a.getStatus());
	}
}
