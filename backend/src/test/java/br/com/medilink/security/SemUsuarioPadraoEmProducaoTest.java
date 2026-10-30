package br.com.medilink.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Produção não tem nenhum usuário de senha, nem o "user" que o Spring Boot criaria por padrão. */
class SemUsuarioPadraoEmProducaoTest {

	@Test
	void repositorioDeUsuariosNasceVazio() {
		var usuarios = new SemUsuarioPadraoEmProducao().semUsuariosDeSenha();

		assertThatThrownBy(() -> usuarios.loadUserByUsername("user")).isInstanceOf(UsernameNotFoundException.class);
		assertThatThrownBy(() -> usuarios.loadUserByUsername("medico@medilink.local")).isInstanceOf(UsernameNotFoundException.class);
	}

	@Test
	void soVale_noPerfilProd() {
		assertThat(SemUsuarioPadraoEmProducao.class.getAnnotation(Profile.class).value()).containsExactly("prod");
	}
}
