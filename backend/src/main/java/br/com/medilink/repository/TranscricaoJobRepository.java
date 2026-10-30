package br.com.medilink.repository;

import br.com.medilink.domain.JobStatus;
import br.com.medilink.domain.TranscricaoJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TranscricaoJobRepository extends JpaRepository<TranscricaoJob, UUID> {

	Optional<TranscricaoJob> findByConsultaId(UUID consultaId);

	List<TranscricaoJob> findByStatusOrderByCriadoEm(JobStatus status);
}
