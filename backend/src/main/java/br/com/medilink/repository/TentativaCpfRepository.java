package br.com.medilink.repository;

import br.com.medilink.domain.TentativaCpf;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface TentativaCpfRepository extends JpaRepository<TentativaCpf, UUID> {

	long countByAgendamentoIdAndMomentoAfter(UUID agendamentoId, Instant desde);
}
