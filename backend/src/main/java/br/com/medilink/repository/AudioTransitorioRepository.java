package br.com.medilink.repository;

import br.com.medilink.domain.AudioTransitorio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AudioTransitorioRepository extends JpaRepository<AudioTransitorio, UUID> {

	List<AudioTransitorio> findByConsultaIdOrderByIniciadoEm(UUID consultaId);
}
