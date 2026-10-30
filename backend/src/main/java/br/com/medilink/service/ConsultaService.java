package br.com.medilink.service;

import br.com.medilink.config.ConsultaProperties;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.transcricao.TranscricaoJobService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Lado do médico na consulta: acompanhar a espera do paciente e aceitá-lo (RF-05). */
@Service
public class ConsultaService {

	/** {@code podeReabrir}: encerrada há pouco e ainda sem transcrição enviada; o médico pode voltar à consulta (T17). */
	public record Detalhe(UUID id, String roomName, ConsultaStatus status, Instant inicioEm, Instant fimEm,
			boolean pacienteAguardando,
			Instant consentimentoEm, String consentimentoVersao, boolean capturaAutorizada, boolean podeReabrir) {
	}

	private final ConsultaRepository consultas;
	private final MedicoRepository medicos;
	private final AuditService auditoria;
	private final Clock clock;
	private final TranscricaoJobService jobs;
	private final TranscricaoJobRepository jobRepo;
	private final ConsultaProperties props;

	public ConsultaService(ConsultaRepository consultas, MedicoRepository medicos, AuditService auditoria, Clock clock,
			TranscricaoJobService jobs, TranscricaoJobRepository jobRepo, ConsultaProperties props) {
		this.consultas = consultas;
		this.medicos = medicos;
		this.auditoria = auditoria;
		this.clock = clock;
		this.jobs = jobs;
		this.jobRepo = jobRepo;
		this.props = props;
	}

	/**
	 * A tela do médico consulta isto a cada poucos segundos; cada chamada é também o sinal de vida dele. Sem sinal por
	 * {@code inatividadeDoMedico}, o servidor encerra a consulta em andamento (aba fechada, queda de internet).
	 */
	@Transactional
	public Detalhe detalhe(String medicoEmail, UUID consultaId) {
		Consulta c = daMedico(medicoEmail, consultaId);
		if (c.getStatus() == ConsultaStatus.EM_ANDAMENTO) {
			c.registrarPresencaDoMedico(Instant.now(clock));
		}
		return detalhe(c);
	}

	/**
	 * Reabre uma consulta encerrada há pouco (por engano ou queda). Só enquanto o job de transcrição ainda não foi
	 * publicado (a janela de tolerância); reabrir cancela o job, que é recriado no próximo encerramento. O consentimento
	 * é mantido e o áudio já capturado fica, para entrar junto na transcrição final.
	 */
	@Transactional
	public Detalhe reabrir(String medicoEmail, UUID consultaId) {
		Consulta c = daMedico(medicoEmail, consultaId);
		if (c.getStatus() == ConsultaStatus.EM_ANDAMENTO) {
			return detalhe(c);
		}
		if (!podeReabrir(c)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O prazo para reabrir esta consulta terminou.");
		}
		Instant agora = Instant.now(clock);
		jobRepo.findByConsultaId(c.getId()).ifPresent(jobRepo::delete);
		c.reabrir(agora);
		auditoria.registrar(medicoEmail, "CONSULTA_REABERTA", "consulta/" + c.getId());
		return detalhe(c);
	}

	/** Encerra as consultas em andamento cujo médico sumiu. Chamado pela rotina de inatividade; devolve quantas. */
	@Transactional
	public int encerrarInativas() {
		Instant agora = Instant.now(clock);
		var inativas = consultas.findByStatusAndUltimaPresencaMedicoEmBefore(ConsultaStatus.EM_ANDAMENTO, agora.minus(props.inatividadeDoMedico()));
		for (Consulta c : inativas) {
			c.encerrar(agora);
			auditoria.registrar("sistema", "CONSULTA_ENCERRADA_INATIVIDADE", "consulta/" + c.getId());
			jobs.criarAoEncerrar(c.getId());
		}
		return inativas.size();
	}

	private boolean podeReabrir(Consulta c) {
		if (c.getStatus() != ConsultaStatus.ENCERRADA || c.getFimEm() == null) {
			return false;
		}
		boolean dentroDoPrazo = !Instant.now(clock).isAfter(c.getFimEm().plus(props.toleranciaReabertura()));
		return dentroDoPrazo && jobRepo.findByConsultaId(c.getId()).map(j -> j.getStatus() == JobStatus.PENDENTE).orElse(false);
	}

	/**
	 * Admite o paciente. Idempotente se a consulta já começou. Com o paciente ainda fora da sala de espera, é
	 * 409: não existe aceite antecipado, para o médico não "pré-autorizar" quem ainda nem passou pelo CPF.
	 */
	@Transactional
	public Detalhe aceitar(String medicoEmail, UUID consultaId) {
		Consulta c = daMedico(medicoEmail, consultaId);
		if (c.getStatus() == ConsultaStatus.EM_ANDAMENTO) {
			return detalhe(c);
		}
		if (c.getStatus() != ConsultaStatus.AGUARDANDO_PACIENTE) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					c.getStatus() == ConsultaStatus.CRIADA ? "O paciente ainda não chegou à sala de espera."
							: "A consulta não está mais aberta.");
		}
		if (c.getConsentimentoEm() == null) { // defesa em profundidade: o paciente nem chega à espera sem consentir
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O paciente ainda não consentiu com o termo.");
		}
		c.admitirPaciente(Instant.now(clock));
		auditoria.registrar(medicoEmail, "PACIENTE_ADMITIDO", "consulta/" + c.getId());
		return detalhe(c);
	}

	/**
	 * Encerra a consulta (evento de saída do médico, T10). Idempotente: o Jitsi pode disparar a saída mais de
	 * uma vez e o cliente pode repetir a chamada. Só o médico encerra; o paciente sair não termina a consulta.
	 * O enfileiramento da transcrição entra na T12.
	 */
	@Transactional
	public Detalhe encerrar(String medicoEmail, UUID consultaId) {
		Consulta c = daMedico(medicoEmail, consultaId);
		if (c.getStatus() == ConsultaStatus.ENCERRADA) {
			return detalhe(c);
		}
		if (c.getStatus() != ConsultaStatus.EM_ANDAMENTO) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A consulta não está em andamento.");
		}
		c.encerrar(Instant.now(clock));
		auditoria.registrar(medicoEmail, "CONSULTA_ENCERRADA", "consulta/" + c.getId());
		jobs.criarAoEncerrar(c.getId()); // RF-06: a transcrição é enfileirada depois que os arquivos de áudio fecham
		return detalhe(c);
	}

	/** Só o médico dono vê a consulta; para os demais ela "não existe". */
	public Consulta daMedico(String medicoEmail, UUID consultaId) {
		var medico = medicos.findByEmail(medicoEmail).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		return consultas.findById(consultaId)
				.filter(c -> c.getAgendamento().getMedico().getId().equals(medico.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private Detalhe detalhe(Consulta c) {
		return new Detalhe(c.getId(), c.getAgendamento().getRoomName(), c.getStatus(), c.getInicioEm(), c.getFimEm(),
				c.getStatus() == ConsultaStatus.AGUARDANDO_PACIENTE, c.getConsentimentoEm(), c.getConsentimentoVersao(),
				c.capturaAutorizada(), podeReabrir(c));
	}
}
