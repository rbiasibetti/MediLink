package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T10: ao sair, o médico encerra a consulta no backend. */
@SpringBootTest
@AutoConfigureMockMvc
class EncerramentoApiTest {

	private static final String CPF = "529.982.247-25";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AuditLogRepository auditoria;

	private String emailMedico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	private record Consulta(String room, String id, String entrada) {
	}

	private Consulta preparar() throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s"}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS)))).andReturn().getResponse().getContentAsString();
		String room = campo(json, "roomName");
		String entrada = campo(mvc.perform(post("/api/salas/" + room + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + CPF + "\"}")).andReturn().getResponse().getContentAsString(), "tokenEntrada");
		String consulta = campo(json, "consultaId");
		mvc.perform(post("/api/consultas/" + consulta + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + entrada + "\",\"aceito\":true,\"versaoTermo\":\"v2\"}")).andExpect(status().isOk());
		return new Consulta(room, consulta, entrada);
	}

	private static String campo(String json, String nome) {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	private void iniciar(Consulta c) throws Exception {
		mvc.perform(post("/api/salas/" + c.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + c.id() + "/aceite").with(user(emailMedico))).andExpect(status().isOk());
	}

	private ResultActions encerrar(Consulta c, String medico) throws Exception {
		return mvc.perform(post("/api/consultas/" + c.id() + "/encerrar").with(user(medico)));
	}

	@Test
	void medicoEncerraAConsultaEmAndamento() throws Exception {
		Consulta c = preparar();
		iniciar(c);

		encerrar(c, emailMedico).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ENCERRADA"))
				.andExpect(jsonPath("$.fimEm").exists())
				.andExpect(jsonPath("$.capturaAutorizada").value(false));
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("CONSULTA_ENCERRADA");
	}

	@Test
	void encerrarDeNovoEhIdempotenteEMantemOFimOriginal() throws Exception {
		Consulta c = preparar();
		iniciar(c);
		String primeiro = encerrar(c, emailMedico).andReturn().getResponse().getContentAsString();

		String segundo = encerrar(c, emailMedico).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(campo(segundo, "fimEm")).isEqualTo(campo(primeiro, "fimEm"));
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao")
				.filteredOn("CONSULTA_ENCERRADA"::equals).hasSize(1);
	}

	@Test
	void consultaQueNaoComecouNaoEncerra() throws Exception {
		Consulta c = preparar();

		encerrar(c, emailMedico).andExpect(status().isConflict());
	}

	@Test
	void soOMedicoDonoEncerra() throws Exception {
		Consulta c = preparar();
		iniciar(c);
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));

		encerrar(c, outro).andExpect(status().isNotFound());
		mvc.perform(post("/api/consultas/" + c.id() + "/encerrar")).andExpect(status().isUnauthorized());
		// O paciente (sem login de médico) também não encerra: a consulta segue em andamento.
		mvc.perform(post("/api/salas/" + c.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isOk());
	}

	@Test
	void depoisDeEncerradaNaoHaMaisCredencialNemConsentimento() throws Exception {
		Consulta c = preparar();
		iniciar(c);
		encerrar(c, emailMedico).andExpect(status().isOk());

		mvc.perform(post("/api/salas/" + c.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\",\"aceito\":true,\"versaoTermo\":\"v1\"}")).andExpect(status().isConflict());
	}
}
