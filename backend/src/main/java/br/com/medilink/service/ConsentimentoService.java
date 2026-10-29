package br.com.medilink.service;

import br.com.medilink.domain.Consulta;
import br.com.medilink.repository.ConsultaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Consentimento do paciente para captação de áudio e transcrição (RF-08). Quem consente é o paciente, que
 * se identifica com o token de entrada (emitido após o CPF) da sala da própria consulta.
 */
@Service
public class ConsentimentoService {

	public record Situacao(boolean consentimentoVigente, String versaoDoTermo) {
	}

	private final ConsultaRepository consultas;
	private final EntradaTokenService entrada;
	private final TermoConsentimento termo;
	private final AuditService auditoria;
	private final Clock clock;

	public ConsentimentoService(ConsultaRepository consultas, EntradaTokenService entrada, TermoConsentimento termo,
			AuditService auditoria, Clock clock) {
		this.consultas = consultas;
		this.entrada = entrada;
		this.termo = termo;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	/**
	 * @param aceito {@code true} registra o consentimento; {@code false} recusa ou retira um já dado
	 * @param versaoDoTermo versão que o paciente leu; precisa ser a vigente, senão ele não viu o texto atual
	 */
	@Transactional
	public Situacao registrar(UUID consultaId, String tokenEntrada, boolean aceito, String versaoDoTermo) {
		Consulta c = consultas.findById(consultaId).orElseThrow(ConsentimentoService::negado);
		// Mesma resposta para consulta inexistente e token inválido, expirado ou de outra sala.
		if (tokenEntrada == null || !entrada.valido(tokenEntrada, c.getAgendamento().getRoomName())) {
			throw negado();
		}
		if (!c.aceitaConsentimento()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A consulta não está mais aberta.");
		}
		String recurso = "consulta/" + c.getId();

		if (!aceito) {
			boolean retirou = c.revogarConsentimento();
			auditoria.registrar("paciente", retirou ? "CONSENTIMENTO_REVOGADO" : "CONSENTIMENTO_RECUSADO", recurso);
			return new Situacao(false, null);
		}
		if (!termo.atual().versao().equals(versaoDoTermo)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O termo foi atualizado. Leia a versão vigente.");
		}
		c.registrarConsentimento(Instant.now(clock), termo.atual().versao());
		auditoria.registrar("paciente", "CONSENTIMENTO_REGISTRADO", recurso);
		return new Situacao(true, c.getConsentimentoVersao());
	}

	private static ResponseStatusException negado() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível validar o acesso.");
	}
}
