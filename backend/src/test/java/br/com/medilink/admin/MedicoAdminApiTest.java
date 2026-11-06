package br.com.medilink.admin;

import br.com.medilink.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static br.com.medilink.admin.AdminTeste.bearer;
import static br.com.medilink.admin.AdminTeste.campo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T20: o administrador cadastra médicos, redefine a senha, ativa/desativa e decide quem precisa de TOTP. */
@SpringBootTest
@AutoConfigureMockMvc
class MedicoAdminApiTest {

	private static final String SENHA = "senha-do-medico-123";

	@Autowired MockMvc mvc;
	@Autowired AdminAuthService auth;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private String admin;
	private String email;

	@BeforeEach
	void montar() throws Exception {
		admin = bearer(AdminTeste.token(mvc, auth));
		email = "dr-" + UUID.randomUUID() + "@clinica.test";
	}

	private ResultActions criar(String emailMedico, String senha, boolean exigirTotp) throws Exception {
		return mvc.perform(post("/api/admin/medicos").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("""
				{"nome":"Dr. João Pereira","identificadorProfissional":"CRM-RS 4321","email":"%s","senha":"%s","exigirTotp":%s}
				""".formatted(emailMedico, senha, exigirTotp)));
	}

	@Test
	void cadastraMedicoSemDevolverSenhaNemSegredo() throws Exception {
		ResultActions r = criar(email, SENHA, false).andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.ativo").value(true))
				.andExpect(jsonPath("$.temSenha").value(true)).andExpect(jsonPath("$.totpHabilitado").value(false))
				.andExpect(content().string(not(containsString(SENHA)))).andExpect(content().string(not(containsString("senhaHash"))));
		String id = campo(r, "id");

		assertThat(jdbc.queryForObject("SELECT senha_hash FROM medico WHERE id = ?", String.class, UUID.fromString(id))).startsWith("$2").doesNotContain(SENHA);
		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + id)).extracting("acao").contains("MEDICO_CRIADO");
		mvc.perform(get("/api/admin/medicos").header("Authorization", admin)).andExpect(status().isOk())
				.andExpect(content().string(containsString(email))).andExpect(content().string(not(containsString(SENHA))));
	}

	@Test
	void recusaEmailRepetidoSenhaCurtaEEmailInvalido() throws Exception {
		criar(email, SENHA, false).andExpect(status().isCreated());
		criar(email, SENHA, false).andExpect(status().isConflict());
		criar(email.toUpperCase(), SENHA, false).andExpect(status().isConflict());
		criar("outro-" + email, "curta", false).andExpect(status().isUnprocessableEntity());
		criar("sem-arroba", SENHA, false).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void soAdministradorMexeNosMedicos() throws Exception {
		mvc.perform(get("/api/admin/medicos")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/medicos").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/medicos").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("medico@x.test")))
				.andExpect(status().isForbidden());
	}

	@Test
	void alteraDadosRedefineSenhaEDesativa() throws Exception {
		String id = campo(criar(email, SENHA, false).andExpect(status().isCreated()), "id");

		mvc.perform(put("/api/admin/medicos/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nome\":\"Dr. João P. Silva\",\"identificadorProfissional\":\"CRM-RS 9999\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Dr. João P. Silva")).andExpect(jsonPath("$.identificadorProfissional").value("CRM-RS 9999"));
		mvc.perform(post("/api/admin/medicos/" + id + "/senha").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"senha\":\"curta\"}")).andExpect(status().isUnprocessableEntity());
		mvc.perform(post("/api/admin/medicos/" + id + "/senha").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
				.content("{\"senha\":\"outra-senha-bem-longa\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/admin/medicos/" + id + "/desativar").header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
		mvc.perform(post("/api/admin/medicos/" + id + "/ativar").header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(true));
		mvc.perform(post("/api/admin/medicos/" + UUID.randomUUID() + "/desativar").header("Authorization", admin)).andExpect(status().isNotFound());

		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + id)).extracting("acao")
				.contains("MEDICO_ALTERADO", "MEDICO_SENHA_REDEFINIDA", "MEDICO_DESATIVADO", "MEDICO_ATIVADO");
	}

	@Test
	void administradorLigaEDesligaOTotpDeCadaMedico() throws Exception {
		String id = campo(criar(email, SENHA, true).andExpect(status().isCreated()).andExpect(jsonPath("$.totpHabilitado").value(true))
				.andExpect(jsonPath("$.totpConfirmado").value(false)), "id");

		mvc.perform(post("/api/admin/medicos/" + id + "/totp/desabilitar").header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totpHabilitado").value(false));
		mvc.perform(post("/api/admin/medicos/" + id + "/totp/habilitar").header("Authorization", admin))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totpHabilitado").value(true));

		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + id)).extracting("acao")
				.contains("MEDICO_TOTP_DESABILITADO", "MEDICO_TOTP_HABILITADO");
	}
}
