package br.com.medilink.repository;

import br.com.medilink.domain.Consulta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsultaRepository extends JpaRepository<Consulta, UUID> {

	Optional<Consulta> findByAgendamentoId(UUID agendamentoId);
}
