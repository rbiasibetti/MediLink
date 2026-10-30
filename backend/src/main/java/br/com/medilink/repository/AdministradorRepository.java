package br.com.medilink.repository;

import br.com.medilink.domain.Administrador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdministradorRepository extends JpaRepository<Administrador, UUID> {

	Optional<Administrador> findByEmail(String email);
}
