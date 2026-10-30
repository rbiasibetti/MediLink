package br.com.medilink.integracao;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.domain.Integrador;
import br.com.medilink.domain.Medico;
import br.com.medilink.integracao.ApiKeyService.Autenticada;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.service.AgendamentoService;
import br.com.medilink.service.AgendamentoService.ParaEmail;
import br.com.medilink.service.AuditService;
import br.com.medilink.service.Cpf;
import br.com.medilink.service.CpfHasher;
import br.com.medilink.service.EmailService;
import br.com.medilink.webhook.WebhookDestino;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agendamentos pedidos por sistemas integrados (T16). Toda operação é restrita ao sistema dono da API key: o que é de
 * outro sistema "não existe" (404). A chave da reunião devolvida é o id do agendamento, que não tem relação com o
 * {@code roomName} (o segredo do link do paciente).
 */
@Service
public class IntegracaoAgendamentoService {

	/** Pedido já normalizado pelo controller (data resolvida em Instant, textos aparados). */
	public record Pedido(String medicoNome, String medicoChave, String referencia, Instant dataHora, String pacienteNome,
			String pacienteCpf, String pacienteEmail, boolean notificar, String webhookCaminho) {
		@Override
		public String toString() {
			return "Pedido[referencia=" + referencia + "]";
		}
	}

	/** Campos a alterar; {@code null} = não mexe, texto vazio = remove (CPF, e-mail e webhook). */
	public record Alteracao(String medicoChave, Instant dataHora, String pacienteNome, String pacienteCpf, String pacienteEmail,
			boolean notificar, String webhookCaminho) {
		@Override
		public String toString() {
			return "Alteracao[]";
		}
	}

	/** {@code emailEnviado}: resultado REAL do envio quando foi pedido (nulo se não houve pedido). */
	public record Reuniao(Agendamento agendamento, Consulta consulta, String link, boolean criado, Boolean emailEnviado) {
		Reuniao(Agendamento agendamento, Consulta consulta, String link, boolean criado) {
			this(agendamento, consulta, link, criado, null);
		}
	}

	private final MedicoRepository medicos;
	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final IntegradorRepository integradores;
	private final CpfHasher cpfHasher;
	private final AgendamentoService agendamentoService;
	private final EmailService email;
	private final LimiteDeUso limite;
	private final IntegracaoProperties props;
	private final AuditService auditoria;
	private final Clock clock;
	private final TransactionTemplate tx;
	/** Último e-mail por agendamento: no máximo um por minuto para o mesmo paciente. */
	private final Map<UUID, Long> ultimoEmail = new ConcurrentHashMap<>();

	public IntegracaoAgendamentoService(MedicoRepository medicos, AgendamentoRepository agendamentos, ConsultaRepository consultas,
			IntegradorRepository integradores, CpfHasher cpfHasher, AgendamentoService agendamentoService, EmailService email,
			LimiteDeUso limite, IntegracaoProperties props, AuditService auditoria, Clock clock,
			PlatformTransactionManager txManager) {
		this.medicos = medicos;
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.integradores = integradores;
		this.cpfHasher = cpfHasher;
		this.agendamentoService = agendamentoService;
		this.email = email;
		this.limite = limite;
		this.props = props;
		this.auditoria = auditoria;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Cria o agendamento, ou devolve o que já existe para a mesma referência (idempotente, sem reenviar e-mail). */
	public Reuniao criar(Autenticada a, Pedido p) {
		Reuniao r = tx.execute(s -> criarNaTransacao(a, p));
		if (r.criado() && p.notificar()) {
			boolean enviado = notificar(a, r.agendamento().getId());
			return new Reuniao(r.agendamento(), r.consulta(), r.link(), true, enviado);
		}
		return r;
	}

	private Reuniao criarNaTransacao(Autenticada a, Pedido p) {
		var existente = agendamentos.findByIntegradorIdAndReferenciaExterna(a.integradorId(), p.referencia());
		if (existente.isPresent()) {
			Agendamento ag = existente.get();
			return new Reuniao(ag, consultas.findByAgendamentoId(ag.getId()).orElseThrow(), agendamentoService.linkPaciente(ag.getRoomName()), false);
		}
		validarData(p.dataHora());
		Medico medico = medicoDe(a.integradorId(), p.medicoChave(), p.medicoNome());
		String cpfHmac = temTexto(p.pacienteCpf()) ? hashDoCpf(p.pacienteCpf()) : null;
		String destino = destinoDoWebhook(a.integradorId(), p.webhookCaminho());
		Agendamento ag = new Agendamento(medico, p.pacienteNome(), cpfHmac, vazioParaNulo(p.pacienteEmail()), p.dataHora(),
				AgendamentoService.novoRoomName(), cpfHmac != null, Instant.now(clock));
		ag.definirIntegracao(a.integradorId(), p.referencia(), destino);
		ag = agendamentos.save(ag);
		Consulta c = consultas.save(new Consulta(ag));
		auditoria.registrar(ator(a), "AGENDAMENTO_CRIADO", "agendamento/" + ag.getId());
		return new Reuniao(ag, c, agendamentoService.linkPaciente(ag.getRoomName()), true);
	}

	@Transactional
	public Reuniao alterar(Autenticada a, UUID chave, Alteracao alt) {
		Agendamento ag = doIntegrador(a, chave);
		Consulta c = consultas.findByAgendamentoId(ag.getId()).orElseThrow();
		if (ag.getStatus() != AgendamentoStatus.AGENDADO || !naoComecou(c)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A reunião não pode mais ser alterada (cancelada ou já iniciada).");
		}
		if (alt.medicoChave() != null && !alt.medicoChave().equals(ag.getMedico().getChaveExterna())) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Não é possível trocar o médico; cancele e crie outra reunião.");
		}
		if (alt.dataHora() != null) {
			validarData(alt.dataHora());
			ag.reagendar(alt.dataHora());
		}
		String nome = alt.pacienteNome() != null ? alt.pacienteNome() : ag.getPacienteNome();
		String emailNovo = alt.pacienteEmail() != null ? vazioParaNulo(alt.pacienteEmail()) : ag.getPacienteEmail();
		String cpfHmac = ag.getPacienteCpfHmac();
		if (alt.pacienteCpf() != null) {
			cpfHmac = alt.pacienteCpf().isBlank() ? null : hashDoCpf(alt.pacienteCpf());
		}
		ag.alterarPaciente(nome, emailNovo, cpfHmac, cpfHmac != null);
		if (alt.webhookCaminho() != null) {
			ag.alterarWebhookDestino(destinoDoWebhook(a.integradorId(), alt.webhookCaminho()));
		}
		auditoria.registrar(ator(a), "AGENDAMENTO_ALTERADO", "agendamento/" + ag.getId());
		return new Reuniao(ag, c, agendamentoService.linkPaciente(ag.getRoomName()), false);
	}

	/** Reenvio do e-mail pedido na edição (data nova, por exemplo). Fora da transação, como na criação. */
	public boolean notificar(Autenticada a, UUID agendamentoId) {
		if (!limite.permitirEmail(a.keyId())) {
			return false; // acima do limite por minuto da key: não envia (a resposta mostra enviada=false)
		}
		long agora = clock.millis();
		Long ultimo = ultimoEmail.get(agendamentoId);
		if (ultimo != null && agora - ultimo < 60_000) {
			return false;
		}
		ParaEmail dados = tx.execute(s -> {
			Agendamento ag = agendamentos.findByIdAndIntegradorId(agendamentoId, a.integradorId()).orElseThrow();
			if (ag.getPacienteEmail() == null) {
				return null;
			}
			return new ParaEmail(ag.getId(), ag.getPacienteNome(), ag.getPacienteEmail(), ag.getMedico().getNome(), ag.getDataHora(),
					ag.isCpfValidationEnabled(), agendamentoService.linkPaciente(ag.getRoomName()));
		});
		if (dados == null) {
			return false;
		}
		try {
			email.enviarLink(ator(a), dados);
			ultimoEmail.put(agendamentoId, agora);
			return true;
		}
		catch (ResponseStatusException falhou) {
			return false; // já auditado como EMAIL_LINK_FALHA; a criação/edição não falha por causa do e-mail
		}
	}

	/** Cancela (idempotente). Só antes de a consulta começar: depois disso existe registro clínico e a retenção manda. */
	@Transactional
	public void cancelar(Autenticada a, UUID chave) {
		Agendamento ag = doIntegrador(a, chave);
		if (ag.getStatus() == AgendamentoStatus.CANCELADO) {
			return;
		}
		Consulta c = consultas.findByAgendamentoId(ag.getId()).orElseThrow();
		if (ag.getStatus() != AgendamentoStatus.AGENDADO || !naoComecou(c)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A reunião já foi iniciada ou realizada e não pode ser excluída.");
		}
		ag.cancelar();
		auditoria.registrar(ator(a), "AGENDAMENTO_CANCELADO", "agendamento/" + ag.getId());
	}

	@Transactional(readOnly = true)
	public Reuniao buscar(Autenticada a, UUID chave) {
		Agendamento ag = doIntegrador(a, chave);
		return new Reuniao(ag, consultas.findByAgendamentoId(ag.getId()).orElseThrow(), agendamentoService.linkPaciente(ag.getRoomName()), false);
	}

	// ---------------------------------------------------------------- apoio

	/** O que é de outro sistema (ou não existe) responde 404. */
	Agendamento doIntegrador(Autenticada a, UUID chave) {
		return agendamentos.findByIdAndIntegradorId(chave, a.integradorId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private Medico medicoDe(UUID integradorId, String chave, String nome) {
		return medicos.findByIntegradorIdAndChaveExterna(integradorId, chave).map(m -> {
			if (!m.getNome().equals(nome)) {
				m.renomear(nome);
			}
			return m;
		}).orElseGet(() -> medicos.save(Medico.externo(nome, chave, emailSintetico(integradorId, chave), integradorId)));
	}

	/** Não entregável (".invalid") e único por sistema+chave: só identifica o médico no login por link. */
	static String emailSintetico(UUID integradorId, String chave) {
		try {
			String h = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((integradorId + "|" + chave).getBytes(StandardCharsets.UTF_8)));
			return "ext-" + h.substring(0, 24) + "@integracao.medilink.invalid";
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String destinoDoWebhook(UUID integradorId, String caminho) {
		String validado = WebhookDestino.validarCaminho(caminho);
		if (validado == null) {
			return null;
		}
		Integrador i = integradores.findById(integradorId).orElseThrow();
		if (!i.isWebhookAtivo() || i.getWebhookBaseUrl() == null) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Este sistema não tem webhook cadastrado.");
		}
		return validado;
	}

	private String hashDoCpf(String cpf) {
		if (!Cpf.valido(cpf)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "CPF inválido.");
		}
		return cpfHasher.hash(cpf);
	}

	private void validarData(Instant quando) {
		Instant agora = Instant.now(clock);
		if (quando == null || !quando.isAfter(agora)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A data e o horário precisam estar no futuro.");
		}
		if (quando.isAfter(agora.plus(props.horizonte()))) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A data está além do horizonte permitido.");
		}
	}

	static boolean naoComecou(Consulta c) {
		return c.getStatus() == ConsultaStatus.CRIADA || c.getStatus() == ConsultaStatus.AGUARDANDO_PACIENTE;
	}

	private static boolean temTexto(String s) {
		return s != null && !s.isBlank();
	}

	private static String vazioParaNulo(String s) {
		return temTexto(s) ? s.strip() : null;
	}

	static String ator(Autenticada a) {
		return "api-key/" + a.keyId();
	}
}
