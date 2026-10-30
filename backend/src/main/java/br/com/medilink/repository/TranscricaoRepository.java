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

	/** Só os ids (a retenção não precisa decifrar o texto para decidir). Transcrição ainda não entregue por webhook nunca é apagada. */
	@Query("select t.consulta.id from Transcricao t where t.criadaEm < :limite and not exists (select 1 from WebhookEntrega w where w.consultaId = t.consulta.id "
			+ "and w.evento = 'transcricao.concluida' and w.status <> br.com.medilink.domain.WebhookEntrega.Status.ENTREGUE)")
	List<UUID> consultasComTranscricaoCriadaAntesDe(@Param("limite") Instant limite);

	@Modifying
	@Query("delete from Transcricao t where t.consulta.id = :consultaId")
	int deleteByConsultaId(@Param("consultaId") UUID consultaId);
}
