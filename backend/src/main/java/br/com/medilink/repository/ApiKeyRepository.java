package br.com.medilink.repository;

import br.com.medilink.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

	List<ApiKey> findByIntegradorIdOrderByCriadaEm(UUID integradorId);
}
