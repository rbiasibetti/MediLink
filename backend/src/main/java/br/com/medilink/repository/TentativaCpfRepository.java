package br.com.medilink.repository;

import br.com.medilink.domain.TentativaCpf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface TentativaCpfRepository extends JpaRepository<TentativaCpf, UUID> {

	long countByAgendamentoIdAndMomentoAfter(UUID agendamentoId, Instant desde);

	/** Retenção (T14): apaga de uma vez as tentativas antigas e devolve quantas. */
	@Modifying
	@Query("delete from TentativaCpf t where t.momento < :limite")
	int deleteByMomentoBefore(@Param("limite") Instant limite);
}
