package br.com.medilink.repository;

import br.com.medilink.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

	List<AuditLog> findByRecursoOrderByMomento(String recurso);
}
