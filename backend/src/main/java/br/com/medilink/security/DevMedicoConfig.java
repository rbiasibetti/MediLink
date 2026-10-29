package br.com.medilink.security;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.MedicoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** Só no perfil "dev": um médico de exemplo com login em memória. Credenciais em application-dev.properties. */
@Configuration
@Profile("dev")
class DevMedicoConfig {

	@Bean
	UserDetailsService devUsers(@Value("${medilink.dev.medico-email}") String email,
			@Value("${medilink.dev.medico-password}") String senha) {
		return new InMemoryUserDetailsManager(User.withUsername(email).password("{noop}" + senha).roles("MEDICO").build());
	}

	@Bean
	CommandLineRunner devMedico(MedicoRepository medicos, @Value("${medilink.dev.medico-email}") String email) {
		return args -> {
			if (medicos.findByEmail(email).isEmpty()) {
				medicos.save(new Medico("Dr(a). Exemplo", "CRM-DEV 0001", email));
			}
		};
	}
}
