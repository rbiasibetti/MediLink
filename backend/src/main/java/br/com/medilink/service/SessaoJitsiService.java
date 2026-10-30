package br.com.medilink.service;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.Consulta;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.service.AgendamentoService.Criado;
import br.com.medilink.service.JitsiTokenService.Participante;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Entrega a credencial da sala do Jitsi (T07). O médico a recebe por ser dono do agendamento; o paciente só
 * a recebe trocando o token de entrada, que o AcessoService emite depois do CPF (ou com a validação desativada).
 */
@Service
public class SessaoJitsiService {

	public enum Estado { AGUARDANDO_MEDICO, ADMITIDO }

	/** {@code jwt} só vem preenchido quando o estado é ADMITIDO (ou para o médico). */
	public record Sessao(Estado estado, String roomName, UUID consultaId, String jwt) {
	}

	private final AgendamentoService agendamentos;
	private final AgendamentoRepository agendamentoRepo;
	private final ConsultaRepository consultas;
	private final EntradaTokenService entrada;
	private final JitsiTokenService jitsi;
	private final AuditService auditoria;

	public SessaoJitsiService(AgendamentoService agendamentos, AgendamentoRepository agendamentoRepo,
			ConsultaRepository consultas, EntradaTokenService entrada, JitsiTokenService jitsi,
			AuditService auditoria) {
		this.agendamentos = agendamentos;
		this.agendamentoRepo = agendamentoRepo;
		this.consultas = consultas;
		this.entrada = entrada;
		this.jitsi = jitsi;
		this.auditoria = auditoria;
	}

	/** Host: moderador (admite o paciente pelo lobby). Só o médico dono; para os demais o agendamento não existe. */
	@Transactional
	public Sessao paraMedico(String medicoEmail, UUID agendamentoId) {
		Criado c = agendamentos.buscar(medicoEmail, agendamentoId);
		Agendamento a = c.agendamento();
		String jwt = jitsi.emitir(a.getRoomName(),
				new Participante(a.getMedico().getId().toString(), a.getMedico().getNome(), null, true));
		auditoria.registrar(medicoEmail, "TOKEN_JITSI_MEDICO", "agendamento/" + a.getId());
		return new Sessao(Estado.ADMITIDO, a.getRoomName(), c.consulta().getId(), jwt);
	}

	/**
	 * Paciente. A credencial do Jitsi só sai depois do aceite do médico (RF-05); antes disso a chamada registra
	 * a chegada e devolve AGUARDANDO_MEDICO, e o paciente repete a chamada até ser admitido. É o backend, e não
	 * o lobby do Jitsi, quem guarda a porta: a sala do Jitsi nasce aberta e, sem credencial, ninguém entra nela.
	 * Qualquer falha dá a mesma resposta (token inválido, expirado, de outra sala, sala inexistente, agendamento
	 * cancelado ou consulta já encerrada). A única resposta diferente é o 409 de falta de consentimento, que o
	 * paciente (já autenticado pelo token) precisa receber para saber o que fazer.
	 */
	@Transactional
	public Sessao paraPaciente(String roomName, String tokenEntrada) {
		if (tokenEntrada == null || !entrada.valido(tokenEntrada, roomName)) {
			throw negado();
		}
		Agendamento a = agendamentoRepo.findByRoomName(roomName)
				.filter(x -> x.getStatus() == AgendamentoStatus.AGENDADO)
				.orElseThrow(SessaoJitsiService::negado);
		Consulta consulta = consultas.findByAgendamentoId(a.getId()).orElseThrow(SessaoJitsiService::negado);

		// Toda consulta é registrada: sem o consentimento ao termo o paciente não chega nem à espera. Vale também para
		// quem retirou o consentimento e tenta voltar à sala (a credencial do Jitsi não é reemitida).
		if (!consulta.aceitaConsentimento()) {
			throw negado(); // consulta encerrada: nada a dizer
		}
		if (consulta.getConsentimentoEm() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O consentimento ao termo é necessário para o atendimento por vídeo.");
		}
		if (consulta.registrarChegadaDoPaciente()) {
			auditoria.registrar("paciente", "PACIENTE_NA_ESPERA", "consulta/" + consulta.getId());
		}
		switch (consulta.getStatus()) {
			case AGUARDANDO_PACIENTE:
				return new Sessao(Estado.AGUARDANDO_MEDICO, a.getRoomName(), consulta.getId(), null);
			case EM_ANDAMENTO:
				break;
			default:
				throw negado();
		}
		// O identificador é por agendamento, não pelo nome: dois pacientes homônimos não se confundem.
		String jwt = jitsi.emitir(a.getRoomName(),
				new Participante("paciente-" + a.getId(), a.getPacienteNome(), null, false));
		auditoria.registrar("paciente", "TOKEN_JITSI_PACIENTE", "agendamento/" + a.getId());
		return new Sessao(Estado.ADMITIDO, a.getRoomName(), consulta.getId(), jwt);
	}

	private static ResponseStatusException negado() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível validar o acesso.");
	}
}
