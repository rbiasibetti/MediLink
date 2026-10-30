package br.com.medilink.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Em produção não existe login de médico por senha (o HTTP Basic é provisório e só tem usuário no perfil "dev"):
 * o médico entra pelo link de uso único emitido pelo sistema integrado (T17). Sem este bean, o Spring Boot cria sozinho
 * um usuário "user" com senha aleatória escrita no log; aqui o repositório de usuários nasce vazio.
 */
@Configuration
@Profile("prod")
class SemUsuarioPadraoEmProducao {

	@Bean
	UserDetailsService semUsuariosDeSenha() {
		return new InMemoryUserDetailsManager();
	}
}
