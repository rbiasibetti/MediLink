package br.com.medilink.repository;

import br.com.medilink.domain.Transcricao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TranscricaoRepository extends JpaRepository<Transcricao, UUID> {

	Optional<Transcricao> findByConsultaId(UUID consultaId);
}
