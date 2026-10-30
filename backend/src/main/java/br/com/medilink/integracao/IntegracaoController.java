package br.com.medilink.integracao;

import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.integracao.ApiKeyService.Autenticada;
import br.com.medilink.integracao.IntegracaoAgendamentoService.Alteracao;
import br.com.medilink.integracao.IntegracaoAgendamentoService.Pedido;
import br.com.medilink.integracao.IntegracaoAgendamentoService.Reuniao;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * API de integração (T16): sistemas externos criam, alteram, consultam e cancelam reuniões com a API key. Os escopos
 * são conferidos em {@code SecurityConfig}. Nada daqui devolve CPF, e-mail ou texto de transcrição.
 */
@RestController
@RequestMapping("/api/integracao/v1/agendamentos")
class IntegracaoController {

	private static final String CHAVE = "[A-Za-z0-9._:@/\\-]+";

	record MedicoDto(@NotBlank @Size(max = 200) String nome, @NotBlank @Size(max = 50) @Pattern(regexp = CHAVE) String chave) {
	}

	/** Data e horário: ou {@code dataHora} (ISO-8601 com fuso) ou {@code data} + {@code horario} (+ {@code fuso}, padrão America/Sao_Paulo). */
	record QuandoDto(OffsetDateTime dataHora, LocalDate data, LocalTime horario, @Size(max = 64) String fuso) {
	}

	record AgendamentoDto(@NotBlank @Size(max = 100) @Pattern(regexp = CHAVE) String chave, @NotNull @Valid QuandoDto quando) {
	}

	record PacienteDto(@NotBlank @Size(max = 200) String nome, @Size(max = 14) String cpf, @Email @Size(max = 254) String email) {
		@Override
		public String toString() {
			return "PacienteDto[]";
		}
	}

	record WebhookDto(@Size(max = 500) String caminho) {
		@Override
		public String toString() {
			return "WebhookDto[]";
		}
	}

	record CriarRequest(@NotNull @Valid MedicoDto medico, @NotNull @Valid AgendamentoDto agendamento,
			@NotNull @Valid PacienteDto paciente, Boolean notificar, @Valid WebhookDto webhook) {
		@Override
		public String toString() {
			return "CriarRequest[]";
		}
	}

	/** Tudo opcional: o que vier substitui; texto vazio em cpf/email/webhook remove. */
	record AlterarRequest(@Valid MedicoDto medico, @Valid QuandoDto quando, @Valid PacientePatch paciente, Boolean notificar,
			@Valid WebhookDto webhook) {
		@Override
		public String toString() {
			return "AlterarRequest[]";
		}
	}

	record PacientePatch(@Size(max = 200) String nome, @Size(max = 14) String cpf, @Email @Size(max = 254) String email) {
		@Override
		public String toString() {
			return "PacientePatch[]";
		}
	}

	record Notificacao(boolean solicitada, boolean enviada) {
	}

	record ReuniaoResposta(UUID chaveReuniao, String referencia, String linkPaciente, AgendamentoStatus status, Instant dataHora,
			boolean cpfObrigatorio, ConsultaStatus consulta, Notificacao notificacao) {
	}

	private final IntegracaoAgendamentoService service;
	private final IntegracaoProperties props;

	IntegracaoController(IntegracaoAgendamentoService service, IntegracaoProperties props) {
		this.service = service;
		this.props = props;
	}

	@PostMapping
	ResponseEntity<ReuniaoResposta> criar(@Valid @RequestBody CriarRequest req, @AuthenticationPrincipal Autenticada a) {
		boolean notificar = Boolean.TRUE.equals(req.notificar());
		Reuniao r = service.criar(a, new Pedido(req.medico().nome().strip(), req.medico().chave().strip(), req.agendamento().chave().strip(),
				quando(req.agendamento().quando()), req.paciente().nome().strip(), req.paciente().cpf(), req.paciente().email(),
				notificar, req.webhook() == null ? null : req.webhook().caminho()));
		var corpo = resposta(r, notificar && r.criado() && temEmail(req.paciente().email()), r.emailEnviado());
		return ResponseEntity.status(r.criado() ? HttpStatus.CREATED : HttpStatus.OK).body(corpo);
	}

	@PatchMapping("/{chave}")
	ReuniaoResposta alterar(@PathVariable UUID chave, @Valid @RequestBody AlterarRequest req, @AuthenticationPrincipal Autenticada a) {
		boolean notificar = Boolean.TRUE.equals(req.notificar());
		var p = req.paciente();
		Instant dataHora = req.quando() == null ? null : quando(req.quando());
		Reuniao r = service.alterar(a, chave, new Alteracao(req.medico() == null ? null : req.medico().chave(), dataHora,
				p == null ? null : p.nome(), p == null ? null : p.cpf(), p == null ? null : p.email(), notificar,
				req.webhook() == null ? null : req.webhook().caminho()));
		Boolean enviado = notificar ? service.notificar(a, chave) : null;
		return resposta(r, notificar, enviado);
	}

	@DeleteMapping("/{chave}")
	ResponseEntity<Void> cancelar(@PathVariable UUID chave, @AuthenticationPrincipal Autenticada a) {
		service.cancelar(a, chave);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{chave}")
	ReuniaoResposta consultar(@PathVariable UUID chave, @AuthenticationPrincipal Autenticada a) {
		return resposta(service.buscar(a, chave), false, null);
	}

	private ReuniaoResposta resposta(Reuniao r, boolean notificacaoSolicitada, Boolean enviado) {
		var ag = r.agendamento();
		return new ReuniaoResposta(ag.getId(), ag.getReferenciaExterna(), r.link(), ag.getStatus(), ag.getDataHora(),
				ag.isCpfValidationEnabled(), r.consulta().getStatus(), new Notificacao(notificacaoSolicitada, Boolean.TRUE.equals(enviado)));
	}

	/** Resolve o horário pedido num instante; exige uma das duas formas, nunca as duas. */
	private Instant quando(QuandoDto q) {
		boolean iso = q.dataHora() != null;
		boolean partes = q.data() != null || q.horario() != null;
		if (iso == partes) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe dataHora (com fuso) ou data e horario, mas não os dois.");
		}
		if (iso) {
			return q.dataHora().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		}
		if (q.data() == null || q.horario() == null) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe data e horario.");
		}
		try {
			ZoneId fuso = ZoneId.of(q.fuso() == null || q.fuso().isBlank() ? "America/Sao_Paulo" : q.fuso());
			return q.data().atTime(q.horario()).atZone(fuso).toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		}
		catch (DateTimeException e) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Fuso horário inválido.");
		}
	}

	private static boolean temEmail(String e) {
		return e != null && !e.isBlank();
	}
}
