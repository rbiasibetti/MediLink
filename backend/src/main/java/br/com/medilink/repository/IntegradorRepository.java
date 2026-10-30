package br.com.medilink.repository;

import br.com.medilink.domain.Integrador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IntegradorRepository extends JpaRepository<Integrador, UUID> {
}
