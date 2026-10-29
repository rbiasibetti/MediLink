package br.com.medilink.service;

import br.com.medilink.domain.AuditLog;
import br.com.medilink.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

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
}
