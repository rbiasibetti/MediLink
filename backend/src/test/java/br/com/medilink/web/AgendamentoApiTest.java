package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T04 / RF-01 e RF-02. */
@SpringBootTest
@AutoConfigureMockMvc
class AgendamentoApiTest {

	private static final String CPF = "529.982.247-25";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AgendamentoRepository agendamentos;
	@Autowired AuditLogRepository auditoria;

	private String emailMedico;
	private Medico medico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medico = medicos.save(new Medico("Dra. Ana", "CRM-SP 123456", emailMedico));
	}

	private static String corpo(String cpf, String dataHora, String extra) {
		return """
				{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s"%s}
				""".formatted(cpf, dataHora, extra);
	}

	private static String amanha() {
		return Instant.now().plus(1, ChronoUnit.DAYS).toString();
	}

	@Test
	void criaAgendamentoComLinkUnicoEMedicoComoHost() throws Exception {
		MvcResult res = mvc.perform(post("/api/agendamentos").with(user(emailMedico))
						.contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, amanha(), ",\"pacienteEmail\":\"maria@mail.test\"")))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.medicoId").value(medico.getId().toString()))
				.andExpect(jsonPath("$.cpfValidationEnabled").value(true))
				.andExpect(jsonPath("$.status").value("AGENDADO"))
				.andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("https://meet.medilink.test/sala/consulta-")))
				.andReturn();

		String json = res.getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"roomName\":\"(consulta-[0-9a-f]{32})\"").matcher(json);
		assertThat(m.find()).isTrue();
		assertThat(agendamentos.findByRoomName(m.group(1))).isPresent();
		assertThat(json).doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("pacienteCpf");
	}

	@Test
	void linkEhUnicoPorAgendamento() throws Exception {
		String a = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content(corpo(CPF, amanha(), ""))).andReturn().getResponse().getContentAsString();
		String b = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content(corpo(CPF, amanha(), ""))).andReturn().getResponse().getContentAsString();

		assertThat(a).isNotEqualTo(b);
		assertThat(agendamentos.count()).isGreaterThanOrEqualTo(2);
	}

	@Test
	void validacaoDeCpfPodeSerDesativadaNoAgendamento() throws Exception {
		mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, amanha(), ",\"cpfValidationEnabled\":false")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.cpfValidationEnabled").value(false));
	}

	@Test
	void cpfNaoEstaEmClaroNoBanco() throws Exception {
		mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content(corpo(CPF, amanha(), ""))).andExpect(status().isCreated());

		// Sem CPF (agendamento por API sem CPF) o hash é nulo; com CPF é sempre o HMAC, nunca o número.
		assertThat(agendamentos.findAll()).allSatisfy(a -> {
			if (a.getPacienteCpfHmac() != null) {
				assertThat(a.getPacienteCpfHmac()).hasSize(64).doesNotContain("52998224725");
			}
		});
	}

	@Test
	void rejeitaCpfInvalidoSemVazarOValor() throws Exception {
		mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
						.content(corpo("111.111.111-11", amanha(), "")))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("111.111.111"))));
	}

	@Test
	void rejeitaDataNoPassadoEEmailInvalido() throws Exception {
		mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, Instant.now().minus(1, ChronoUnit.DAYS).toString(), "")))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, amanha(), ",\"pacienteEmail\":\"nao-e-email\"")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void exigeAutenticacao() throws Exception {
		mvc.perform(post("/api/agendamentos").contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, amanha(), "")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void usuarioAutenticadoSemCadastroDeMedicoEhProibido() throws Exception {
		mvc.perform(post("/api/agendamentos").with(user("intruso@x.test")).contentType(MediaType.APPLICATION_JSON)
						.content(corpo(CPF, amanha(), "")))
				.andExpect(status().isForbidden());
	}

	@Test
	void soODonoConsegueConsultarOAgendamento() throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content(corpo(CPF, amanha(), ""))).andReturn().getResponse().getContentAsString();
		String id = json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/agendamentos/" + id).with(user(emailMedico))).andExpect(status().isOk());

		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));
		mvc.perform(get("/api/agendamentos/" + id).with(user(outro))).andExpect(status().isNotFound());
	}

	@Test
	void criacaoEhAuditadaSemDadoSensivel() throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content(corpo(CPF, amanha(), ""))).andReturn().getResponse().getContentAsString();
		String id = json.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

		var logs = auditoria.findByRecursoOrderByMomento("agendamento/" + id);
		assertThat(logs).singleElement().satisfies(l -> {
			assertThat(l.getAtor()).isEqualTo(emailMedico);
			assertThat(l.getAcao()).isEqualTo("AGENDAMENTO_CRIADO");
		});
	}
}
