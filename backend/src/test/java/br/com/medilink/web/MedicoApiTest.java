package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.MedicoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MedicoApiTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;

	@Test
	void devolveNomeEEmailDoMedicoAutenticado() throws Exception {
		String email = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 1", email));

		mvc.perform(get("/api/me").with(user(email)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nome").value("Dra. Ana Lima"))
				.andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void exigeAutenticacaoEUsuarioSemCadastroDeMedicoEhProibido() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").with(user("intruso@x.test"))).andExpect(status().isForbidden());
	}
}
