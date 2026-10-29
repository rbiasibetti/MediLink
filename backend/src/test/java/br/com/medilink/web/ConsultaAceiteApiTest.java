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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T08 / RF-05: o paciente aguarda e só recebe a credencial depois do aceite explícito do médico. */
@SpringBootTest
@AutoConfigureMockMvc
class ConsultaAceiteApiTest {

	private static final String CPF = "529.982.247-25";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private String emailMedico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	private record Sala(String room, String consultaId, String entrada) {
	}

	/** Cria o agendamento e já passa o paciente pela porta do CPF (ou pela flag desativada). */
	private Sala preparar(boolean cpfValidation) throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s","cpfValidationEnabled":%s}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS), cpfValidation)))
				.andReturn().getResponse().getContentAsString();
		String room = grupo(json, "roomName"), consulta = grupo(json, "consultaId");
		String entrada = grupo(mvc.perform(post("/api/salas/" + room + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + CPF + "\"}")).andReturn().getResponse().getContentAsString(), "tokenEntrada");
		return new Sala(room, consulta, entrada);
	}

	private static String grupo(String json, String campo) {
		Matcher m = Pattern.compile("\"" + campo + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", campo, json).isTrue();
		return m.group(1);
	}

	private ResultActions pedirToken(Sala s) throws Exception {
		return mvc.perform(post("/api/salas/" + s.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + s.entrada() + "\"}"));
	}

	private ResultActions aceitar(Sala s, String medico) throws Exception {
		return mvc.perform(post("/api/consultas/" + s.consultaId() + "/aceite").with(user(medico)));
	}

	@Test
	void pacienteFicaEmEsperaSemCredencialAteOAceite() throws Exception {
		Sala s = preparar(true);

		pedirToken(s).andExpect(status().isAccepted())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.estado").value("AGUARDANDO_MEDICO"))
				.andExpect(jsonPath("$.jwt").doesNotExist());
		pedirToken(s).andExpect(status().isAccepted()).andExpect(jsonPath("$.jwt").doesNotExist());

		mvc.perform(get("/api/consultas/" + s.consultaId()).with(user(emailMedico)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("AGUARDANDO_PACIENTE"))
				.andExpect(jsonPath("$.pacienteAguardando").value(true));
	}

	@Test
	void aposOAceiteOPacienteRecebeOJwt() throws Exception {
		Sala s = preparar(true);
		pedirToken(s).andExpect(status().isAccepted());

		aceitar(s, emailMedico).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("EM_ANDAMENTO"))
				.andExpect(jsonPath("$.inicioEm").exists())
				.andExpect(jsonPath("$.pacienteAguardando").value(false));

		pedirToken(s).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("ADMITIDO"))
				.andExpect(jsonPath("$.jwt").isNotEmpty());
	}

	@Test
	void naoHaAceiteAntecipadoSeOPacienteAindaNaoChegou() throws Exception {
		Sala s = preparar(true);

		aceitar(s, emailMedico).andExpect(status().isConflict());
		// E o paciente continua sem credencial: chega agora e fica em espera.
		pedirToken(s).andExpect(status().isAccepted()).andExpect(jsonPath("$.jwt").doesNotExist());
	}

	@Test
	void validacaoDeCpfDesativadaNaoDispensaOAceite() throws Exception {
		Sala s = preparar(false);

		pedirToken(s).andExpect(status().isAccepted()).andExpect(jsonPath("$.jwt").doesNotExist());
	}

	@Test
	void aceiteEhIdempotente() throws Exception {
		Sala s = preparar(true);
		pedirToken(s);

		aceitar(s, emailMedico).andExpect(status().isOk());
		aceitar(s, emailMedico).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_ANDAMENTO"));
	}

	@Test
	void soOMedicoDonoVeEAceita() throws Exception {
		Sala s = preparar(true);
		pedirToken(s);
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));

		aceitar(s, outro).andExpect(status().isNotFound());
		mvc.perform(get("/api/consultas/" + s.consultaId()).with(user(outro))).andExpect(status().isNotFound());
		mvc.perform(post("/api/consultas/" + s.consultaId() + "/aceite")).andExpect(status().isUnauthorized());
		// Nenhuma dessas tentativas admitiu o paciente.
		pedirToken(s).andExpect(status().isAccepted());
	}

	@Test
	void pacienteSemTokenDeEntradaNemChegaAEntrarEmEspera() throws Exception {
		Sala s = preparar(true);

		mvc.perform(post("/api/salas/" + s.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"lixo\"}")).andExpect(status().isForbidden());
		mvc.perform(get("/api/consultas/" + s.consultaId()).with(user(emailMedico)))
				.andExpect(jsonPath("$.status").value("CRIADA")).andExpect(jsonPath("$.pacienteAguardando").value(false));
	}

	@Test
	void consultaEncerradaNaoDaMaisCredencial() throws Exception {
		Sala s = preparar(true);
		pedirToken(s);
		aceitar(s, emailMedico).andExpect(status().isOk());
		jdbc.update("UPDATE consulta SET status = 'ENCERRADA' WHERE id = ?", UUID.fromString(s.consultaId()));

		pedirToken(s).andExpect(status().isForbidden());
		aceitar(s, emailMedico).andExpect(status().isConflict());
	}

	@Test
	void respostaDoPacienteNaoVazaDadosNemJwtEmEspera() throws Exception {
		Sala s = preparar(true);

		pedirToken(s).andExpect(content().string(not(containsString("eyJ")))).andExpect(content().string(not(containsString("Maria"))));
	}

	@Test
	void chegadaEAceiteSaoAuditados() throws Exception {
		Sala s = preparar(true);
		pedirToken(s);
		pedirToken(s);
		aceitar(s, emailMedico);

		var logs = auditoria.findByRecursoOrderByMomento("consulta/" + s.consultaId());
		assertThat(logs).extracting("acao").containsExactly("PACIENTE_NA_ESPERA", "PACIENTE_ADMITIDO");
	}
}
