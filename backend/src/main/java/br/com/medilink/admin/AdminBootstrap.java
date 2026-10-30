package br.com.medilink.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Cria o primeiro administrador na subida, se configurado e se ainda não houver nenhum. Nunca registra a senha. */
@Component
class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
	private final AdminAuthService auth;

	AdminBootstrap(AdminAuthService auth) {
		this.auth = auth;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			if (auth.criarPrimeiroSeNecessario()) {
				log.info("Administração: primeiro administrador criado a partir da configuração (cadastre o TOTP no primeiro login)");
			}
		}
		catch (RuntimeException e) {
			log.warn("Administração: não foi possível criar o primeiro administrador ({})", e.getClass().getSimpleName());
		}
	}
}
