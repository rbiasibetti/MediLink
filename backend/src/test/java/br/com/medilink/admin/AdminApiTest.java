package br.com.medilink.admin;

import br.com.medilink.integracao.ApiKeyService;
import br.com.medilink.repository.AdministradorRepository;
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

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T18: login do administrador (senha + TOTP), sessão, bloqueio e isolamento em relação a médico e API key. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

	private static final String SENHA = "uma-senha-bem-longa-123";

	@Autowired MockMvc mvc;
	@Autowired AdminAuthService auth;
	@Autowired AdministradorRepository admins;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private String email;

	@BeforeEach
	void montar() {
		email = "admin-" + UUID.randomUUID() + "@clinica.test";
		auth.criar("teste", email, SENHA);
	}

	private ResultActions login(String senha, String codigo) throws Exception {
		String cod = codigo == null ? "" : ",\"codigo\":\"" + codigo + "\"";
		return mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"" + cod + "}"));
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(r.andReturn().getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private static String codigoAgora(String segredo) {
		return Totp.gerar(segredo, Totp.passo(Instant.now().getEpochSecond()));
	}

	/** Primeiro acesso completo: senha, recebe o segredo, confirma com um código e entra. */
	private String entrar() throws Exception {
		String segredo = campo(login(SENHA, null).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("ENROLAR_TOTP")), "segredoTotp");
		return campo(login(SENHA, codigoAgora(segredo)).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("OK")), "token");
	}

	@Test
	void primeiroAcessoCadastraOTotpEDepoisSoEntraComCodigo() throws Exception {
		ResultActions r = login(SENHA, null).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("ENROLAR_TOTP"))
				.andExpect(jsonPath("$.otpauthUri").value(containsString("otpauth://totp/"))).andExpect(jsonPath("$.token").doesNotExist());
		String segredo = campo(r, "segredoTotp");
		assertThat(jdbc.queryForObject("SELECT totp_segredo FROM administrador WHERE email = ?", String.class, email)).startsWith("enc:").doesNotContain(segredo);

		login(SENHA, "000000").andExpect(status().isForbidden());                       // código errado no enrolamento
		String token = campo(login(SENHA, codigoAgora(segredo)).andExpect(jsonPath("$.etapa").value("OK")), "token");
		assertThat(token).isNotBlank();

		// Depois de confirmado, a senha sozinha não basta e o mesmo código não vale duas vezes.
		login(SENHA, null).andExpect(status().isForbidden());
		login(SENHA, codigoAgora(segredo)).andExpect(status().isForbidden());          // replay do mesmo passo
		long proximo = Totp.passo(Instant.now().getEpochSecond()) + 1;
		login(SENHA, Totp.gerar(segredo, proximo)).andExpect(status().isOk());       // passo seguinte (dentro da tolerância de ±1)
	}

	@Test
	void falhasDeTodoTipoDaoOMesmo403SemDizerOMotivo() throws Exception {
		login("senha-errada-123456", null).andExpect(status().isForbidden()).andExpect(content().string(not(containsString("senha"))));
		mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"naoexiste@x.test\",\"senha\":\"" + SENHA + "\"}")).andExpect(status().isForbidden())
				.andExpect(content().string(not(containsString("existe"))));
		assertThat(auditoria.findAll().stream().filter(a -> a.getAcao().equals("ADMIN_LOGIN_FALHA")).count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void cincoFalhasBloqueiamPorUmTempoMesmoComASenhaCerta() throws Exception {
		for (int i = 0; i < 5; i++) {
			login("errada-" + i + "-123456789", null).andExpect(status().isForbidden());
		}

		login(SENHA, null).andExpect(status().isTooManyRequests());
		assertThat(auditoria.findAll().stream().filter(a -> a.getAcao().equals("ADMIN_LOGIN_BLOQUEADO")).count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void sessaoDeAdminAbreAAreaAdministrativaESemEllaNaoHaAcesso() throws Exception {
		String token = entrar();

		mvc.perform(get("/api/admin/painel").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
				.andExpect(jsonPath("$.agendamentos").exists()).andExpect(jsonPath("$.transcricao").exists());
		mvc.perform(get("/api/admin/painel")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/painel").header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/integradores")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/admin/termos")).andExpect(status().isUnauthorized());
	}

	@Test
	void adminDesativadoPerdeOAcessoNaHora() throws Exception {
		String token = entrar();
		mvc.perform(get("/api/admin/painel").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		jdbc.update("UPDATE administrador SET ativo = 0 WHERE email = ?", email);

		mvc.perform(get("/api/admin/painel").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		login(SENHA, null).andExpect(status().isForbidden());
	}

	@Test
	void medicoEApiKeyNaoEntramNaAdministracaoENemAdminNaAreaDoMedico() throws Exception {
		org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.class.getName();
		mvc.perform(get("/api/admin/painel").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("medico@x.test")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/painel").header("X-API-Key", "mlk_" + "0".repeat(32) + "_x")).andExpect(status().isUnauthorized());
		// E a sessão do administrador não vale nas rotas do médico.
		String token = entrar();
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + UUID.randomUUID()).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}

	@Test
	void politicaDeSenhaEUnicidadeDoEmail() throws Exception {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.criar("t", "x-" + UUID.randomUUID() + "@x.test", "curta"))
				.hasMessageContaining("12 a 128");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.criar("t", "nao-e-email", SENHA)).hasMessageContaining("E-mail inválido");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> auth.criar("t", email, SENHA)).hasMessageContaining("Já existe");
		assertThat(admins.findByEmail(email).orElseThrow().getSenhaHash()).startsWith("$2").doesNotContain(SENHA);
	}

	@Test
	void adminCriaOutroEnaoDesativaASiMesmo() throws Exception {
		String token = entrar();
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		ResultActions r = mvc.perform(post("/api/admin/administradores").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + outro + "\",\"senha\":\"" + SENHA + "\"}")).andExpect(status().isCreated());
		String idOutro = campo(r, "id");
		UUID meuId = admins.findByEmail(email).orElseThrow().getId();

		mvc.perform(delete("/api/admin/administradores/" + meuId).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
		mvc.perform(delete("/api/admin/administradores/" + idOutro).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
		assertThat(auditoria.findAll().stream().filter(a -> a.getAcao().equals("ADMIN_CRIADO") || a.getAcao().equals("ADMIN_DESATIVADO")).count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void totpBateComOVetorDaRfc6238() {
		// RFC 6238, Apêndice B (SHA-1): segredo ASCII "12345678901234567890", T = 59 s -> 94287082 (os 6 últimos dígitos: 287082).
		String segredoBase32 = Totp.base32("12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

		assertThat(Totp.gerar(segredoBase32, 59 / 30)).isEqualTo("287082");
		assertThat(Totp.gerar(segredoBase32, 1111111109L / 30)).isEqualTo("081804");
	}
}
