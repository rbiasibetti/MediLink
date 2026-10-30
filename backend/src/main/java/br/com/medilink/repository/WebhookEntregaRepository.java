package br.com.medilink.repository;

import br.com.medilink.domain.WebhookEntrega;
import br.com.medilink.domain.WebhookEntrega.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEntregaRepository extends JpaRepository<WebhookEntrega, UUID> {

	Optional<WebhookEntrega> findByConsultaIdAndEvento(UUID consultaId, String evento);

	@Query("select w from WebhookEntrega w where w.status = br.com.medilink.domain.WebhookEntrega.Status.PENDENTE and w.proximaTentativaEm <= :agora order by w.proximaTentativaEm")
	List<WebhookEntrega> vencidas(@Param("agora") Instant agora, Pageable limite);

	List<WebhookEntrega> findByStatusOrderByCriadoEmDesc(Status status, Pageable limite);

	List<WebhookEntrega> findAllByOrderByCriadoEmDesc(Pageable limite);
}
