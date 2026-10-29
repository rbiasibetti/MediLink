package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T05 / RF-03. */
@SpringBootTest
@AutoConfigureMockMvc
class EnvioEmailApiTest {

	private static final String CPF = "529.982.247-25";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AuditLogRepository auditoria;
	@MockitoBean JavaMailSender mail;

	private String emailMedico;

	@BeforeEach
	void preparar() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
		when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
	}

	private String criar(String extra, boolean cpfValidation) throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s","cpfValidationEnabled":%s%s}
								""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS), cpfValidation, extra)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
	}

	@Test
	void enviaEmailComLinkERegistraNaAuditoria() throws Exception {
		String id = criar(",\"pacienteEmail\":\"maria@mail.test\"", true);

		mvc.perform(post("/api/agendamentos/" + id + "/enviar-email").with(user(emailMedico)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enviadoEm").exists());

		ArgumentCaptor<MimeMessage> msg = ArgumentCaptor.forClass(MimeMessage.class);
		verify(mail).send(msg.capture());
		MimeMessage m = msg.getValue();
		assertThat(m.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("maria@mail.test");
		assertThat(m.getFrom()[0].toString()).isEqualTo("teste@medilink.test");
		assertThat(m.getSubject()).contains("Dra. Ana Lima").contains("às");
		String corpo = (String) m.getContent();
		assertThat(corpo).contains("https://meet.medilink.test/sala/consulta-").contains("Maria Souza")
				.contains("Tenha seu CPF em mãos").doesNotContain("529.982").doesNotContain("52998224725");
		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + id))
				.extracting("acao").contains("EMAIL_LINK_ENVIADO");
	}

	@Test
	void naoPedeCpfNoEmailQuandoAValidacaoEstaDesativada() throws Exception {
		String id = criar(",\"pacienteEmail\":\"maria@mail.test\"", false);

		mvc.perform(post("/api/agendamentos/" + id + "/enviar-email").with(user(emailMedico))).andExpect(status().isOk());

		ArgumentCaptor<MimeMessage> msg = ArgumentCaptor.forClass(MimeMessage.class);
		verify(mail).send(msg.capture());
		assertThat((String) msg.getValue().getContent()).doesNotContain("CPF");
	}

	@Test
	void agendamentoSemEmailDoPacienteNaoEnvia() throws Exception {
		String id = criar("", true);

		mvc.perform(post("/api/agendamentos/" + id + "/enviar-email").with(user(emailMedico)))
				.andExpect(status().isUnprocessableEntity());
		verify(mail, never()).send(any(MimeMessage.class));
	}

	@Test
	void soODonoPodeEnviar() throws Exception {
		String id = criar(",\"pacienteEmail\":\"maria@mail.test\"", true);
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));

		mvc.perform(post("/api/agendamentos/" + id + "/enviar-email").with(user(outro))).andExpect(status().isNotFound());
		verify(mail, never()).send(any(MimeMessage.class));
	}

	@Test
	void exigeAutenticacao() throws Exception {
		mvc.perform(post("/api/agendamentos/" + UUID.randomUUID() + "/enviar-email")).andExpect(status().isUnauthorized());
	}

	@Test
	void falhaDoRelayViraBadGatewaySemVazarDetalhesEFicaAuditada() throws Exception {
		String id = criar(",\"pacienteEmail\":\"maria@mail.test\"", true);
		doThrow(new MailSendException("550 maria@mail.test rejeitado")).when(mail).send(any(MimeMessage.class));

		mvc.perform(post("/api/agendamentos/" + id + "/enviar-email").with(user(emailMedico)))
				.andExpect(status().isBadGateway())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("maria@mail.test"))));

		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + id)).extracting("acao")
				.contains("EMAIL_LINK_FALHA").doesNotContain("EMAIL_LINK_ENVIADO");
	}
}
