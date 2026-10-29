package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TentativaCpfRepository;
import br.com.medilink.service.EntradaTokenService;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T06 / RF-04: porta de entrada do paciente, sem autenticação de médico. */
@SpringBootTest
@AutoConfigureMockMvc
class AcessoSalaApiTest {

	private static final String CPF = "529.982.247-25";
	private static final String OUTRO_CPF = "111.444.777-35";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AgendamentoRepository agendamentos;
	@Autowired AuditLogRepository auditoria;
	@Autowired TentativaCpfRepository tentativas;
	@Autowired EntradaTokenService tokens;

	private String emailMedico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	/** Cria um agendamento e devolve {roomName, id}. */
	private String[] criar(boolean cpfValidation) throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s","cpfValidationEnabled":%s}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS), cpfValidation)))
				.andReturn().getResponse().getContentAsString();
		Matcher room = Pattern.compile("\"roomName\":\"([^\"]+)\"").matcher(json);
		Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
		assertThat(room.find() && id.find()).isTrue();
		return new String[] { room.group(1), id.group(1) };
	}

	private ResultActions validar(String room, String cpf) throws Exception {
		return mvc.perform(post("/api/salas/" + room + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + cpf + "\"}"));
	}

	private static String tokenDe(ResultActions r) throws Exception {
		String json = r.andReturn().getResponse().getContentAsString();
		return json.replaceAll(".*\"tokenEntrada\":\"([^\"]+)\".*", "$1");
	}

	@Test
	void telaDeAcessoMostraSoOMinimoESemAutenticacao() throws Exception {
		String[] sala = criar(true);

		mvc.perform(get("/api/salas/" + sala[0] + "/acesso"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.medicoNome").value("Dra. Ana Lima"))
				.andExpect(jsonPath("$.cpfObrigatorio").value(true))
				.andExpect(jsonPath("$.dataHora").exists())
				.andExpect(content().string(not(containsString("Maria"))))
				.andExpect(content().string(not(containsString("529.982"))))
				.andExpect(content().string(not(containsString("52998224725"))));
	}

	@Test
	void salaInexistenteNaTelaDeAcesso() throws Exception {
		mvc.perform(get("/api/salas/consulta-nao-existe/acesso")).andExpect(status().isNotFound());
	}

	@Test
	void cpfCorretoLiberaEEmiteTokenDeEntradaDaSala() throws Exception {
		String[] sala = criar(true);

		ResultActions r = validar(sala[0], CPF).andExpect(status().isOk()).andExpect(jsonPath("$.expiraEm").exists());

		String token = tokenDe(r);
		assertThat(tokens.valido(token, sala[0])).isTrue();
		assertThat(tokens.valido(token, "outra-sala")).isFalse();
	}

	@Test
	void cpfSemMascaraTambemConfere() throws Exception {
		String[] sala = criar(true);

		validar(sala[0], "52998224725").andExpect(status().isOk());
	}

	@Test
	void cpfDivergenteBloqueia() throws Exception {
		String[] sala = criar(true);

		validar(sala[0], OUTRO_CPF).andExpect(status().isForbidden())
				.andExpect(content().string(not(containsString("tokenEntrada"))));
	}

	@Test
	void semCpfComValidacaoAtivaBloqueia() throws Exception {
		String[] sala = criar(true);

		mvc.perform(post("/api/salas/" + sala[0] + "/validar-cpf")).andExpect(status().isForbidden());
		validar(sala[0], "").andExpect(status().isForbidden());
		validar(sala[0], "123").andExpect(status().isForbidden());
	}

	@Test
	void validacaoDesativadaLiberaSemInformarCpf() throws Exception {
		String[] sala = criar(false);

		mvc.perform(get("/api/salas/" + sala[0] + "/acesso")).andExpect(jsonPath("$.cpfObrigatorio").value(false));
		ResultActions r = mvc.perform(post("/api/salas/" + sala[0] + "/validar-cpf")).andExpect(status().isOk());
		assertThat(tokens.valido(tokenDe(r), sala[0])).isTrue();
		validar(sala[0], OUTRO_CPF).andExpect(status().isOk());
	}

	@Test
	void falhaEhIndistinguivelEntreCpfErradoESalaInexistente() throws Exception {
		String[] sala = criar(true);

		String errado = validar(sala[0], OUTRO_CPF).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		String inexistente = validar("consulta-nao-existe", OUTRO_CPF).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();

		assertThat(errado.replaceAll("\"instance\":\"[^\"]*\"", "")).isEqualTo(inexistente.replaceAll("\"instance\":\"[^\"]*\"", ""));
	}

	@Test
	void apos5FalhasBloqueiaInclusiveOCpfCorreto() throws Exception {
		String[] sala = criar(true);
		for (int i = 0; i < 5; i++) {
			validar(sala[0], OUTRO_CPF).andExpect(status().isForbidden());
		}

		validar(sala[0], CPF).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
		assertThat(tentativas.countByAgendamentoIdAndMomentoAfter(UUID.fromString(sala[1]), Instant.now().minus(1, ChronoUnit.HOURS))).isEqualTo(5);
	}

	@Test
	void falhasDeUmaSalaNaoBloqueiamOutra() throws Exception {
		String[] a = criar(true);
		String[] b = criar(true);
		for (int i = 0; i < 5; i++) {
			validar(a[0], OUTRO_CPF);
		}

		validar(b[0], CPF).andExpect(status().isOk());
	}

	@Test
	void auditoriaRegistraOsEventosSemCpf() throws Exception {
		String[] sala = criar(true);
		validar(sala[0], OUTRO_CPF);
		validar(sala[0], CPF);

		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + sala[1])).extracting("acao")
				.contains("CPF_RECUSADO", "CPF_CONFIRMADO");
		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + sala[1]))
				.allSatisfy(a -> assertThat(a.getAtor() + a.getAcao() + a.getRecurso())
						.doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("111.444").doesNotContain("11144477735"));
		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + sala[1])).extracting("ator").containsOnly("paciente", emailMedico);
	}
}
