package br.com.medilink.repository;

import br.com.medilink.domain.Transcricao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TranscricaoRepository extends JpaRepository<Transcricao, UUID> {

	Optional<Transcricao> findByConsultaId(UUID consultaId);

	/** Só os ids: a retenção não precisa (nem deve) carregar e decifrar o texto para decidir. */
	@Query("select t.consulta.id from Transcricao t where t.criadaEm < :limite")
	List<UUID> consultasComTranscricaoCriadaAntesDe(@Param("limite") Instant limite);

	@Modifying
	@Query("delete from Transcricao t where t.consulta.id = :consultaId")
	int deleteByConsultaId(@Param("consultaId") UUID consultaId);
}
