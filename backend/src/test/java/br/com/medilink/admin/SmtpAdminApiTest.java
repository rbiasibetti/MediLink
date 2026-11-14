package br.com.medilink.admin;

import br.com.medilink.email.ProvedorDeEmail;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.ConfiguracaoSmtpRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static br.com.medilink.admin.AdminTeste.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Configuração do SMTP pela administração: ler/salvar (senha cifrada, nunca exposta), testar e controle de acesso. */
@SpringBootTest
@AutoConfigureMockMvc
class SmtpAdminApiTest {

	private static final String SENHA_SMTP = "s3nha-do-relay";

	@Autowired MockMvc mvc;
	@Autowired AdminAuthService auth;
	@Autowired ConfiguracaoSmtpRepository repo;
	@Autowired ProvedorDeEmail provedor;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean JavaMailSender mail;

	private String admin;

	@BeforeEach
	void montar() throws Exception {
		admin = bearer(AdminTeste.token(mvc, auth));
		jdbc.update("DELETE FROM configuracao_smtp");        // estado limpo (linha única de id fixo)
		Mockito.when(mail.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
		provedor.recarregar();                                // sem config no banco → usa o mock (padrão do ambiente)
	}

	@AfterEach
	void limpar() {
		jdbc.update("DELETE FROM configuracao_smtp");         // não deixa config para outros testes no banco compartilhado
		provedor.recarregar();
	}

	private String corpo(String senhaJson) {
		return "{\"host\":\"smtp.exemplo.com\",\"porta\":587,\"usuario\":\"no-reply@exemplo.com\"" + senhaJson
				+ ",\"autenticar\":true,\"starttls\":true,\"remetente\":\"no-reply@exemplo.com\"}";
	}

	@Test
	void semConfigMostraOsPadroesDoAmbiente() throws Exception {
		mvc.perform(get("/api/admin/smtp").header("Authorization", admin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configurado").value(false))
				.andExpect(jsonPath("$.host").exists())
				.andExpect(jsonPath("$.temSenha").value(false));
	}

	@Test
	void salvaELeDeVoltaSemExporASenhaQueFicaCifradaNoBanco() throws Exception {
		mvc.perform(put("/api/admin/smtp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(corpo(",\"senha\":\"" + SENHA_SMTP + "\"")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.configurado").value(true))
				.andExpect(jsonPath("$.host").value("smtp.exemplo.com"))
				.andExpect(jsonPath("$.porta").value(587))
				.andExpect(jsonPath("$.temSenha").value(true))
				.andExpect(content().string(not(containsString(SENHA_SMTP))));  // a senha nunca volta

		assertThat(jdbc.queryForObject("SELECT senha_cifrada FROM configuracao_smtp", String.class)).startsWith("enc:").doesNotContain(SENHA_SMTP);
		assertThat(auditoria.findByRecursoOrderByMomento("smtp")).extracting("acao").contains("SMTP_CONFIGURADO");

		mvc.perform(get("/api/admin/smtp").header("Authorization", admin)).andExpect(jsonPath("$.usuario").value("no-reply@exemplo.com"))
				.andExpect(content().string(not(containsString(SENHA_SMTP))));
	}

	@Test
	void salvarSemSenhaMantemASenhaAnterior() throws Exception {
		mvc.perform(put("/api/admin/smtp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(corpo(",\"senha\":\"" + SENHA_SMTP + "\""))).andExpect(status().isOk());
		mvc.perform(put("/api/admin/smtp").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(corpo("")))  // sem senha
				.andExpect(status().isOk()).andExpect(jsonPath("$.temSenha").value(true));
		assertThat(repo.atual().orElseThrow().getSenha()).isEqualTo(SENHA_SMTP);
	}

	@Test
	void testeDeEnvioUsaOEnvioAtualEAuditaOSucessoEaFalha() throws Exception {
		mvc.perform(post("/api/admin/smtp/testar").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"para\":\"ana@exemplo.com\"}"))
				.andExpect(status().isNoContent());
		Mockito.verify(mail).send(ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
		assertThat(auditoria.findByRecursoOrderByMomento("smtp")).extracting("acao").contains("SMTP_TESTADO");

		Mockito.doThrow(new MailSendException("550 recusado")).when(mail).send(ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
		mvc.perform(post("/api/admin/smtp/testar").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content("{\"para\":\"ana@exemplo.com\"}"))
				.andExpect(status().isBadGateway()).andExpect(content().string(not(containsString("550"))));
		assertThat(auditoria.findByRecursoOrderByMomento("smtp")).extracting("acao").contains("SMTP_TESTE_FALHA");
	}

	@Test
	void soAdministradorConfiguraOSmtp() throws Exception {
		mvc.perform(get("/api/admin/smtp")).andExpect(status().isUnauthorized());
		mvc.perform(put("/api/admin/smtp").contentType(MediaType.APPLICATION_JSON).content(corpo(""))).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/smtp/testar").contentType(MediaType.APPLICATION_JSON).content("{\"para\":\"x@y.com\"}")).andExpect(status().isUnauthorized());
	}
}
