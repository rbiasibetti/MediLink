package br.com.medilink.repository;

import br.com.medilink.domain.Medico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MedicoRepository extends JpaRepository<Medico, UUID> {

	Optional<Medico> findByEmail(String email);
}
