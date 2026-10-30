package br.com.medilink.service;

import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
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

	public record Detalhe(UUID id, String roomName, ConsultaStatus status, Instant inicioEm, Instant fimEm,
			boolean pacienteAguardando,
			Instant consentimentoEm, String consentimentoVersao, boolean capturaAutorizada) {
	}

	private final ConsultaRepository consultas;
	private final MedicoRepository medicos;
	private final AuditService auditoria;
	private final Clock clock;
	private final TranscricaoJobService jobs;

	public ConsultaService(ConsultaRepository consultas, MedicoRepository medicos, AuditService auditoria, Clock clock,
			TranscricaoJobService jobs) {
		this.consultas = consultas;
		this.medicos = medicos;
		this.auditoria = auditoria;
		this.clock = clock;
		this.jobs = jobs;
	}

	@Transactional(readOnly = true)
	public Detalhe detalhe(String medicoEmail, UUID consultaId) {
		return detalhe(daMedico(medicoEmail, consultaId));
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
	private Consulta daMedico(String medicoEmail, UUID consultaId) {
		var medico = medicos.findByEmail(medicoEmail).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		return consultas.findById(consultaId)
				.filter(c -> c.getAgendamento().getMedico().getId().equals(medico.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static Detalhe detalhe(Consulta c) {
		return new Detalhe(c.getId(), c.getAgendamento().getRoomName(), c.getStatus(), c.getInicioEm(), c.getFimEm(),
				c.getStatus() == ConsultaStatus.AGUARDANDO_PACIENTE, c.getConsentimentoEm(), c.getConsentimentoVersao(),
				c.capturaAutorizada());
	}
}
