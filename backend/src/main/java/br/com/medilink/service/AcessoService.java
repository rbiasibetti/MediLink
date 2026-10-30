package br.com.medilink.service;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.TentativaCpf;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.TentativaCpfRepository;
import br.com.medilink.service.EntradaTokenService.Emitido;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Porta de entrada do paciente (RF-04). Regras:
 * <ul>
 * <li>Validação ativa: só o CPF correto libera; qualquer falha devolve a mesma resposta genérica, seja
 * CPF errado, sala inexistente ou agendamento cancelado (não revela se algo existe).</li>
 * <li>Após {@value #MAX_FALHAS} falhas em {@link #JANELA} a sala bloqueia, inclusive para o CPF certo,
 * o que torna a força bruta inviável. O custo é que quem tem o link pode atrasar o paciente por até a janela.</li>
 * <li>Validação desativada pelo médico: libera sem pedir CPF.</li>
 * </ul>
 */
@Service
public class AcessoService {

	static final int MAX_FALHAS = 5;
	static final Duration JANELA = Duration.ofMinutes(15);

	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final ValidadeDoLink validade;
	private final TentativaCpfRepository tentativas;
	private final CpfHasher cpfHasher;
	private final EntradaTokenService tokens;
	private final AuditService auditoria;
	private final Clock clock;

	public AcessoService(AgendamentoRepository agendamentos, ConsultaRepository consultas, TentativaCpfRepository tentativas, CpfHasher cpfHasher,
			EntradaTokenService tokens, AuditService auditoria, Clock clock, ValidadeDoLink validade) {
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.validade = validade;
		this.tentativas = tentativas;
		this.cpfHasher = cpfHasher;
		this.tokens = tokens;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	/** Dados mínimos da tela de acesso: nada do paciente, nem o nome. */
	public record Info(String medicoNome, Instant dataHora, boolean cpfObrigatorio) {
	}

	@Transactional(readOnly = true)
	public Info acesso(String roomName) {
		Agendamento a = ativo(roomName).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return new Info(a.getMedico().getNome(), a.getDataHora(), a.isCpfValidationEnabled());
	}

	/** Falhas precisam ser gravadas mesmo lançando exceção, daí o noRollbackFor. */
	@Transactional(noRollbackFor = ResponseStatusException.class)
	public Emitido validar(String roomName, String cpfInformado) {
		Agendamento a = ativo(roomName).orElseThrow(AcessoService::negado);
		String recurso = "agendamento/" + a.getId();

		if (!a.isCpfValidationEnabled()) {
			auditoria.registrar("paciente", "ACESSO_SEM_CPF", recurso);
			return tokens.emitir(a.getRoomName(), a.getId());
		}

		Instant agora = Instant.now(clock);
		if (tentativas.countByAgendamentoIdAndMomentoAfter(a.getId(), agora.minus(JANELA)) >= MAX_FALHAS) {
			auditoria.registrar("paciente", "CPF_BLOQUEADO", recurso);
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Muitas tentativas. Aguarde alguns minutos e tente novamente.") {
				@Override
				public HttpHeaders getHeaders() {
					HttpHeaders h = new HttpHeaders();
					h.set(HttpHeaders.RETRY_AFTER, String.valueOf(JANELA.toSeconds()));
					return h;
				}
			};
		}

		if (cpfInformado == null || !Cpf.valido(cpfInformado)
				|| !cpfHasher.confere(cpfInformado, a.getPacienteCpfHmac())) {
			tentativas.save(new TentativaCpf(a.getId(), agora));
			auditoria.registrar("paciente", "CPF_RECUSADO", recurso);
			throw negado();
		}

		auditoria.registrar("paciente", "CPF_CONFIRMADO", recurso);
		return tokens.emitir(a.getRoomName(), a.getId());
	}

	/**
	 * A consulta da sala, para o paciente que já passou pela porta do CPF: ele precisa do id para responder ao termo
	 * (o consentimento vem ANTES da espera, então ainda não há resposta de /token que o traga).
	 */
	@Transactional(readOnly = true)
	public java.util.UUID consultaDaSala(String roomName) {
		return ativo(roomName).flatMap(a -> consultas.findByAgendamentoId(a.getId())).map(c -> c.getId()).orElseThrow(AcessoService::negado);
	}

	private java.util.Optional<Agendamento> ativo(String roomName) {
		return agendamentos.findByRoomName(roomName).filter(a -> a.getStatus() == AgendamentoStatus.AGENDADO)
				.filter(a -> consultas.findByAgendamentoId(a.getId()).map(c -> !validade.expirado(a, c)).orElse(false));
	}

	private static ResponseStatusException negado() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível validar o acesso.");
	}
}
