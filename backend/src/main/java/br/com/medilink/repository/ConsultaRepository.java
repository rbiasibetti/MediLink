package br.com.medilink.repository;

import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultaRepository extends JpaRepository<Consulta, UUID> {

	Optional<Consulta> findByAgendamentoId(UUID agendamentoId);

	List<Consulta> findByStatusAndUltimaPresencaMedicoEmBefore(ConsultaStatus status, Instant limite);
}
