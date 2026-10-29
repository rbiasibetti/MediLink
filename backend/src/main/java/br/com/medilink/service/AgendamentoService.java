package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AgendamentoService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final MedicoRepository medicos;
	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final CpfHasher cpfHasher;
	private final AuditService auditoria;
	private final MedilinkProperties props;
	private final Clock clock;

	public AgendamentoService(MedicoRepository medicos, AgendamentoRepository agendamentos,
			ConsultaRepository consultas, CpfHasher cpfHasher, AuditService auditoria,
			MedilinkProperties props, Clock clock) {
		this.medicos = medicos;
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.cpfHasher = cpfHasher;
		this.auditoria = auditoria;
		this.props = props;
		this.clock = clock;
	}

	public record Novo(String pacienteNome, String pacienteCpf, String pacienteEmail, Instant dataHora,
			boolean cpfValidationEnabled) {
	}

	public record Criado(Agendamento agendamento, Consulta consulta, String link) {
	}

	/** O médico (host) é sempre o autenticado; a Consulta nasce junto, com a sala predefinida. */
	@Transactional
	public Criado criar(String medicoEmail, Novo novo) {
		Medico medico = medicoAutenticado(medicoEmail);
		Agendamento agendamento = agendamentos.save(new Agendamento(medico, novo.pacienteNome(),
				cpfHasher.hash(novo.pacienteCpf()), novo.pacienteEmail(), novo.dataHora(),
				gerarRoomName(), novo.cpfValidationEnabled(), Instant.now(clock)));
		Consulta consulta = consultas.save(new Consulta(agendamento));
		auditoria.registrar(medicoEmail, "AGENDAMENTO_CRIADO", "agendamento/" + agendamento.getId());
		return new Criado(agendamento, consulta, linkDe(agendamento.getRoomName()));
	}

	/** Só o médico dono enxerga o agendamento; para os demais ele "não existe". */
	@Transactional(readOnly = true)
	public Criado buscar(String medicoEmail, UUID id) {
		Medico medico = medicoAutenticado(medicoEmail);
		Agendamento agendamento = agendamentos.findById(id)
				.filter(a -> a.getMedico().getId().equals(medico.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Consulta consulta = consultas.findByAgendamentoId(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new Criado(agendamento, consulta, linkDe(agendamento.getRoomName()));
	}

	public record ParaEmail(UUID agendamentoId, String pacienteNome, String pacienteEmail, String medicoNome,
			Instant dataHora, boolean cpfValidationEnabled, String link) {
	}

	/** Dados do e-mail do link, já resolvidos dentro da transação (o médico é carregado sob demanda). */
	@Transactional(readOnly = true)
	public ParaEmail paraEnvioDeEmail(String medicoEmail, UUID id) {
		Agendamento a = buscar(medicoEmail, id).agendamento();
		return new ParaEmail(a.getId(), a.getPacienteNome(), a.getPacienteEmail(), a.getMedico().getNome(),
				a.getDataHora(), a.isCpfValidationEnabled(), linkDe(a.getRoomName()));
	}

	private Medico medicoAutenticado(String email) {
		return medicos.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

	private String linkDe(String roomName) {
		return props.publicBaseUrl().replaceAll("/+$", "") + "/sala/" + roomName;
	}

	/** 128 bits aleatórios: a sala não é adivinhável a partir de dados do agendamento. */
	private static String gerarRoomName() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return "consulta-" + HexFormat.of().formatHex(bytes);
	}
}
