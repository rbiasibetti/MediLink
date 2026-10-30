package br.com.medilink.medico;

import br.com.medilink.config.ConsultaProperties;
import br.com.medilink.config.MedilinkProperties;
import br.com.medilink.domain.AcessoMedicoLink;
import br.com.medilink.domain.AcessoMedicoLink.Modo;
import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.domain.Medico;
import br.com.medilink.integracao.ApiKeyService.Autenticada;
import br.com.medilink.repository.AcessoMedicoLinkRepository;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Entrada do médico por link de uso único (T17). O sistema integrado, que autentica o médico do seu lado, pede o link
 * com a API key; o navegador do médico troca o link por uma sessão restrita à consulta. O JWT do Jitsi nunca fica com o
 * sistema integrado. Cada pedido novo invalida os links anteriores ainda não usados.
 */
@Service
public class AcessoMedicoService {

	private static final SecureRandom RANDOM = new SecureRandom();
	/** Teto de links emitidos por consulta em 10 minutos (laço de repetição ou abuso). */
	private static final int MAX_LINKS_POR_10_MIN = 10;

	public record LinkEmitido(String link, Instant expiraEm, Modo modo) {
		@Override
		public String toString() {
			return "LinkEmitido[modo=" + modo + ", expiraEm=" + expiraEm + "]";
		}
	}

	public record Troca(String sessao, Instant expiraEm, UUID agendamentoId, UUID consultaId, Modo modo, String medicoNome) {
		@Override
		public String toString() {
			return "Troca[modo=" + modo + "]";
		}
	}

	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final MedicoRepository medicos;
	private final AcessoMedicoLinkRepository links;
	private final TranscricaoJobRepository jobs;
	private final SessaoMedicoService sessoes;
	private final ConsultaProperties props;
	private final MedilinkProperties base;
	private final AuditService auditoria;
	private final Clock clock;

	public AcessoMedicoService(AgendamentoRepository agendamentos, ConsultaRepository consultas, MedicoRepository medicos,
			AcessoMedicoLinkRepository links, TranscricaoJobRepository jobs, SessaoMedicoService sessoes, ConsultaProperties props,
			MedilinkProperties base, AuditService auditoria, Clock clock) {
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.medicos = medicos;
		this.links = links;
		this.jobs = jobs;
		this.sessoes = sessoes;
		this.props = props;
		this.base = base;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	@Transactional
	public LinkEmitido emitir(Autenticada a, UUID chaveReuniao, String medicoChave, String medicoNome) {
		Agendamento ag = agendamentos.findByIdAndIntegradorId(chaveReuniao, a.integradorId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (ag.getStatus() == AgendamentoStatus.CANCELADO) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A reunião foi cancelada.");
		}
		Medico medico = ag.getMedico();
		if (!medicoChave.equals(medico.getChaveExterna())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este médico não corresponde à reunião.");
		}
		if (!medico.getNome().equals(medicoNome)) {
			medico.renomear(medicoNome);
		}
		Consulta c = consultas.findByAgendamentoId(ag.getId()).orElseThrow();
		Instant agora = Instant.now(clock);
		Modo modo = modoPara(ag, c, agora);

		if (links.countByConsultaIdAndCriadoEmAfter(c.getId(), agora.minusSeconds(600)) >= MAX_LINKS_POR_10_MIN) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Links demais para esta reunião. Aguarde alguns minutos.");
		}
		links.findByConsultaIdAndUsadoEmIsNullAndRevogadoEmIsNull(c.getId()).forEach(l -> l.revogar(agora)); // só o último vale

		byte[] bruto = new byte[32];
		RANDOM.nextBytes(bruto);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bruto);
		Instant expira = agora.plus(props.validadeDoLinkDoMedico());
		links.save(new AcessoMedicoLink(ag.getId(), c.getId(), medico.getId(), hash(token), modo, agora, expira));
		auditoria.registrar("api-key/" + a.keyId(), "ACESSO_MEDICO_LINK_EMITIDO", "consulta/" + c.getId());
		// O token vai no fragmento (#): não chega ao servidor web nem ao Referer.
		return new LinkEmitido(base.publicBaseUrl().replaceAll("/+$", "") + "/medico/acesso#t=" + token, expira, modo);
	}

	/** Troca o token do link por uma sessão. Qualquer falha (inexistente, usado, revogado, vencido) é o mesmo 403. */
	@Transactional
	public Troca trocar(String token) {
		if (token == null || token.length() < 20 || token.length() > 100) {
			throw negado();
		}
		AcessoMedicoLink l = links.findByHashToken(hash(token)).orElseThrow(AcessoMedicoService::negado);
		Instant agora = Instant.now(clock);
		if (links.consumir(l.getId(), agora) != 1) {
			throw negado();
		}
		Agendamento ag = agendamentos.findById(l.getAgendamentoId()).orElseThrow(AcessoMedicoService::negado);
		if (ag.getStatus() == AgendamentoStatus.CANCELADO) {
			throw negado();
		}
		Medico medico = medicos.findById(l.getMedicoId()).orElseThrow(AcessoMedicoService::negado);
		Instant expira = agora.plus(props.validadeDaSessaoDoMedico());
		String sessao = sessoes.emitir(medico.getEmail(), l.getConsultaId(), l.getAgendamentoId(), l.getModo(), expira);
		auditoria.registrar("medico/" + medico.getId(), "ACESSO_MEDICO_LINK_USADO", "consulta/" + l.getConsultaId());
		return new Troca(sessao, expira, l.getAgendamentoId(), l.getConsultaId(), l.getModo(), medico.getNome());
	}

	/** Quando a sala pode ser iniciada; fora disso, só leitura (ou nada, se nunca houve consulta). */
	private Modo modoPara(Agendamento ag, Consulta c, Instant agora) {
		switch (c.getStatus()) {
			case CRIADA, AGUARDANDO_PACIENTE -> {
				if (agora.isBefore(ag.getDataHora().minus(props.janelaAntes()))) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "Cedo demais: a sala só pode ser iniciada a partir de "
							+ props.janelaAntes().toMinutes() + " min antes do horário.");
				}
				if (agora.isAfter(ag.getDataHora().plus(props.janelaDepois()))) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "O prazo para iniciar esta reunião expirou.");
				}
				return Modo.SALA;
			}
			case EM_ANDAMENTO -> {
				return Modo.SALA; // voltando durante a consulta (queda, aba fechada)
			}
			case ENCERRADA -> {
				boolean podeReabrir = c.getFimEm() != null && !agora.isAfter(c.getFimEm().plus(props.toleranciaReabertura()))
						&& jobs.findByConsultaId(c.getId()).map(j -> j.getStatus() == JobStatus.PENDENTE).orElse(false);
				return podeReabrir ? Modo.SALA : Modo.LEITURA;
			}
			default -> {
				return Modo.LEITURA;
			}
		}
	}

	private static ResponseStatusException negado() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Link inválido ou expirado.");
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
