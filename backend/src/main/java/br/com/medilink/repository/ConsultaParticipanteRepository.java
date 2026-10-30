package br.com.medilink.repository;

import br.com.medilink.domain.ConsultaParticipante;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultaParticipanteRepository extends JpaRepository<ConsultaParticipante, UUID> {

	Optional<ConsultaParticipante> findByConsultaIdAndEndpointId(UUID consultaId, String endpointId);

	List<ConsultaParticipante> findByConsultaId(UUID consultaId);
}
