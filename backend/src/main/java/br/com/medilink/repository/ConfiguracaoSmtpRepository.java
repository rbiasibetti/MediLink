package br.com.medilink.repository;

import br.com.medilink.domain.ConfiguracaoSmtp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConfiguracaoSmtpRepository extends JpaRepository<ConfiguracaoSmtp, UUID> {

	default Optional<ConfiguracaoSmtp> atual() {
		return findById(ConfiguracaoSmtp.ID);
	}
}
