package br.com.medilink.repository;

import br.com.medilink.domain.AcessoMedicoLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcessoMedicoLinkRepository extends JpaRepository<AcessoMedicoLink, UUID> {

	Optional<AcessoMedicoLink> findByHashToken(String hashToken);

	long countByConsultaIdAndCriadoEmAfter(UUID consultaId, Instant desde);

	List<AcessoMedicoLink> findByConsultaIdAndUsadoEmIsNullAndRevogadoEmIsNull(UUID consultaId);

	/**
	 * Consome o link de forma atômica: só um chamador consegue marcar o uso, mesmo com duas trocas simultâneas.
	 * @return 1 se este chamador consumiu, 0 se já estava usado, revogado ou vencido
	 */
	@Modifying
	@Query("update AcessoMedicoLink l set l.usadoEm = :agora where l.id = :id and l.usadoEm is null and l.revogadoEm is null and l.expiraEm > :agora")
	int consumir(@Param("id") UUID id, @Param("agora") Instant agora);
}
