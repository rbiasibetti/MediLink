package br.com.medilink.repository;

import br.com.medilink.domain.Agendamento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgendamentoRepository extends JpaRepository<Agendamento, UUID> {

	Optional<Agendamento> findByRoomName(String roomName);

	Optional<Agendamento> findByIdAndIntegradorId(UUID id, UUID integradorId);

	Optional<Agendamento> findByIntegradorIdAndReferenciaExterna(UUID integradorId, String referenciaExterna);
}
