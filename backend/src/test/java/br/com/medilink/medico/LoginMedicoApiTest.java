package br.com.medilink.medico;

import br.com.medilink.admin.MedicoAdminService;
import br.com.medilink.admin.Totp;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T20: login do médico por e-mail e senha, TOTP quando o administrador o habilitou, bloqueio e isolamento das sessões. */
@SpringBootTest
@AutoConfigureMockMvc
class LoginMedicoApiTest {

	private static final String SENHA = "senha-do-medico-123";

	@Autowired MockMvc mvc;
	@Autowired MedicoAdminService medicos;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private String email;
	private UUID medicoId;

	@BeforeEach
	void montar() {
		email = "dr-" + UUID.randomUUID() + "@clinica.test";
		medicoId = medicos.criar("teste", "Dr. João Pereira", "CRM-RS 4321", email, SENHA, false).getId();
	}

	private ResultActions login(String emailLogin, String senha, String codigo) throws Exception {
		String cod = codigo == null ? "" : ",\"codigo\":\"" + codigo + "\"";
		return mvc.perform(post("/api/medico/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + emailLogin + "\",\"senha\":\"" + senha + "\"" + cod + "}"));
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(r.andReturn().getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private static String codigo(String segredo, long deslocamento) {
		return Totp.gerar(segredo, Totp.passo(Instant.now().getEpochSecond()) + deslocamento);
	}

	@Test
	void entraComEmailESenhaEUsaASessao() throws Exception {
		ResultActions r = login(email, SENHA, null).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("OK"))
				.andExpect(jsonPath("$.nome").value("Dr. João Pereira")).andExpect(jsonPath("$.segredoTotp").doesNotExist());
		String token = campo(r, "token");

		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + medicoId)).extracting("acao").contains("MEDICO_LOGIN_OK");
	}

	@Test
	void emailSemDistincaoDeMaiusculas() throws Exception {
		login(email.toUpperCase(), SENHA, null).andExpect(status().isOk());
	}

	@Test
	void senhaErradaEmailInexistenteEMedicoSemSenhaDaoAMesmaResposta() throws Exception {
		String semSenha = "ext-" + UUID.randomUUID() + "@integracao.medilink.invalid";
		jdbc.update("INSERT INTO medico (id, nome, identificador_profissional, email) VALUES (?, 'Externo', 'CRM 1', ?)", UUID.randomUUID(), semSenha);

		String a = login(email, "senha-errada-123456", null).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		String b = login("nao-existe-" + email, SENHA, null).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		String c = login(semSenha, SENHA, null).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertThat(a).isEqualTo(b.replace("nao-existe-", "")).isEqualTo(c.replace(semSenha, email));
		assertThat(a).doesNotContain(SENHA);
	}

	@Test
	void cincoFalhasBloqueiamEAteASenhaCertaFicaRecusada() throws Exception {
		for (int i = 0; i < 5; i++) {
			login(email, "senha-errada-123456", null).andExpect(status().isForbidden());
		}
		login(email, SENHA, null).andExpect(status().isTooManyRequests());
		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + medicoId)).extracting("acao").contains("MEDICO_LOGIN_FALHA", "MEDICO_LOGIN_BLOQUEADO");

		medicos.definirSenha("teste", medicoId, SENHA); // o administrador redefine a senha e libera
		login(email, SENHA, null).andExpect(status().isOk());
	}

	@Test
	void medicoDesativadoNaoEntraENemUsaSessaoQueJaTinha() throws Exception {
		String token = campo(login(email, SENHA, null).andExpect(status().isOk()), "token");
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		medicos.ativar("teste", medicoId, false);

		login(email, SENHA, null).andExpect(status().isForbidden());
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}

	@Test
	void comTotpHabilitadoOPrimeiroLoginEntregaOSegredoEDepoisExigeOCodigo() throws Exception {
		medicos.totp("teste", medicoId, true);

		String segredo = campo(login(email, SENHA, null).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("ENROLAR_TOTP"))
				.andExpect(jsonPath("$.otpauthUri").value(containsString("otpauth://totp/"))).andExpect(jsonPath("$.token").doesNotExist()), "segredoTotp");
		assertThat(jdbc.queryForObject("SELECT totp_segredo FROM medico WHERE id = ?", String.class, medicoId)).startsWith("enc:").doesNotContain(segredo);

		login(email, SENHA, "000000").andExpect(status().isForbidden());
		login(email, SENHA, codigo(segredo, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("OK"));

		login(email, SENHA, null).andExpect(status().isForbidden());                         // senha sozinha não basta mais
		login(email, SENHA, codigo(segredo, 0)).andExpect(status().isForbidden());           // o mesmo código não vale duas vezes
		login(email, SENHA, codigo(segredo, 1)).andExpect(status().isOk());
		assertThat(auditoria.findByRecursoOrderByMomento("medico/" + medicoId)).extracting("acao").contains("MEDICO_TOTP_ENROLAMENTO", "MEDICO_TOTP_CONFIRMADO");
	}

	@Test
	void senhaErradaComTotpHabilitadoNaoRevelaOSegredo() throws Exception {
		medicos.totp("teste", medicoId, true);

		login(email, "senha-errada-123456", null).andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("segredo"))));
	}

	@Test
	void desabilitarOTotpVoltaASoSenha() throws Exception {
		medicos.totp("teste", medicoId, true);
		medicos.totp("teste", medicoId, false);

		login(email, SENHA, null).andExpect(status().isOk()).andExpect(jsonPath("$.etapa").value("OK"));
	}

	@Test
	void ligarDeNovoZeraOSegredoParaTrocarDeCelular() throws Exception {
		medicos.totp("teste", medicoId, true);
		String antigo = campo(login(email, SENHA, null), "segredoTotp");
		login(email, SENHA, codigo(antigo, 0)).andExpect(status().isOk());

		medicos.totp("teste", medicoId, true);

		String novo = campo(login(email, SENHA, null).andExpect(jsonPath("$.etapa").value("ENROLAR_TOTP")), "segredoTotp");
		assertThat(novo).isNotEqualTo(antigo);
	}

	@Test
	void sessaoDeLoginNaoServeParaAdminNemParaOutroMedico() throws Exception {
		String token = campo(login(email, SENHA, null), "token");

		mvc.perform(get("/api/admin/medicos").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + UUID.randomUUID()).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound()); // autenticou: a consulta é que não existe
		mvc.perform(get("/api/consultas")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token + "x")).andExpect(status().isUnauthorized());
	}
}
