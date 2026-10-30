package br.com.medilink.repository;

import br.com.medilink.domain.TermoVersao;
import br.com.medilink.domain.TermoVersao.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TermoVersaoRepository extends JpaRepository<TermoVersao, UUID> {

	Optional<TermoVersao> findByStatus(Status status);

	List<TermoVersao> findAllByOrderByCriadoEmDesc();
}
