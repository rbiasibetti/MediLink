package br.com.medilink.service;

import br.com.medilink.domain.AuditLog;
import br.com.medilink.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Trilha de auditoria. Nunca receber CPF, áudio ou texto de transcrição como argumento. */
@Service
public class AuditService {

	private final AuditLogRepository repo;
	private final Clock clock;

	public AuditService(AuditLogRepository repo, Clock clock) {
		this.repo = repo;
		this.clock = clock;
	}

	public void registrar(String ator, String acao, String recurso) {
		repo.save(new AuditLog(ator, acao, recurso, Instant.now(clock)));
	}

	/**
	 * Registra em transação própria: o registro sobrevive mesmo se a operação que o chamou der erro e desfizer a sua.
	 * Serve para tentativas negadas, que terminam em exceção (404/403) e por isso reverteriam um registro comum.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrarEmTransacaoPropria(String ator, String acao, String recurso) {
		registrar(ator, acao, recurso);
	}
}
