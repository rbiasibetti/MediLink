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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T09 / RF-08: sem consentimento registrado, a captura não é autorizada. */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentimentoApiTest {

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
		return new Consulta(room, campo(json, "consultaId"), entrada);
	}

	private static String campo(String json, String nome) {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", nome, json).isTrue();
		return m.group(1);
	}

	private ResultActions consentir(Consulta c, String token, boolean aceito, String versao) throws Exception {
		return mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"%s\",\"aceito\":%s,\"versaoTermo\":\"%s\"}".formatted(token, aceito, versao)));
	}

	private ResultActions detalhe(Consulta c) throws Exception {
		return mvc.perform(get("/api/consultas/" + c.id()).with(user(emailMedico))).andExpect(status().isOk());
	}

	/** O paciente chega e o médico o aceita: a consulta passa a EM_ANDAMENTO. */
	private void iniciar(Consulta c) throws Exception {
		mvc.perform(post("/api/salas/" + c.room() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + c.id() + "/aceite").with(user(emailMedico))).andExpect(status().isOk());
	}

	@Test
	void termoEPublicoETemVersao() throws Exception {
		mvc.perform(get("/api/termo-consentimento"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.versao").value("v1"))
				.andExpect(content().string(containsString("transcrição")))
				.andExpect(content().string(containsString("apagado")));
	}

	@Test
	void semConsentimentoACapturaNaoEhAutorizadaMesmoComConsultaEmAndamento() throws Exception {
		Consulta c = preparar();
		iniciar(c);

		detalhe(c).andExpect(jsonPath("$.status").value("EM_ANDAMENTO"))
				.andExpect(jsonPath("$.consentimentoEm").doesNotExist())
				.andExpect(jsonPath("$.capturaAutorizada").value(false));
	}

	@Test
	void consentimentoSozinhoNaoAutorizaACapturaAntesDeIniciar() throws Exception {
		Consulta c = preparar();

		consentir(c, c.entrada(), true, "v1").andExpect(status().isOk()).andExpect(jsonPath("$.consentimentoVigente").value(true));

		detalhe(c).andExpect(jsonPath("$.status").value("CRIADA")).andExpect(jsonPath("$.capturaAutorizada").value(false));
	}

	@Test
	void consentimentoMaisConsultaEmAndamentoAutorizamACaptura() throws Exception {
		Consulta c = preparar();
		consentir(c, c.entrada(), true, "v1").andExpect(status().isOk());
		iniciar(c);

		detalhe(c).andExpect(jsonPath("$.capturaAutorizada").value(true))
				.andExpect(jsonPath("$.consentimentoEm").exists())
				.andExpect(jsonPath("$.consentimentoVersao").value("v1"));
	}

	@Test
	void consentirComAConsultaJaEmAndamentoTambemFunciona() throws Exception {
		Consulta c = preparar();
		iniciar(c);

		consentir(c, c.entrada(), true, "v1").andExpect(status().isOk());

		detalhe(c).andExpect(jsonPath("$.capturaAutorizada").value(true));
	}

	@Test
	void recusarNaoBloqueiaAConsultaMasNaoHaCaptura() throws Exception {
		Consulta c = preparar();

		consentir(c, c.entrada(), false, "v1").andExpect(status().isOk()).andExpect(jsonPath("$.consentimentoVigente").value(false));
		iniciar(c);

		detalhe(c).andExpect(jsonPath("$.status").value("EM_ANDAMENTO")).andExpect(jsonPath("$.capturaAutorizada").value(false));
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("CONSENTIMENTO_RECUSADO");
	}

	@Test
	void retirarOConsentimentoInterrompeAAutorizacao() throws Exception {
		Consulta c = preparar();
		consentir(c, c.entrada(), true, "v1");
		iniciar(c);
		detalhe(c).andExpect(jsonPath("$.capturaAutorizada").value(true));

		consentir(c, c.entrada(), false, "v1").andExpect(status().isOk());

		detalhe(c).andExpect(jsonPath("$.capturaAutorizada").value(false)).andExpect(jsonPath("$.consentimentoEm").doesNotExist());
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao")
				.contains("CONSENTIMENTO_REGISTRADO", "CONSENTIMENTO_REVOGADO");
	}

	@Test
	void guardaAVersaoDoTermoNoBanco() throws Exception {
		Consulta c = preparar();
		consentir(c, c.entrada(), true, "v1");

		String versao = jdbc.queryForObject("SELECT consentimento_versao FROM consulta WHERE id = ?", String.class, UUID.fromString(c.id()));
		assertThat(versao).isEqualTo("v1");
	}

	@Test
	void versaoDeTermoDiferenteDaVigenteEhRejeitada() throws Exception {
		Consulta c = preparar();

		consentir(c, c.entrada(), true, "v0").andExpect(status().isConflict());
		detalhe(c).andExpect(jsonPath("$.consentimentoEm").doesNotExist());
	}

	@Test
	void soConsenteQuemPassouPelaPortaDoCpfDaPropriaSala() throws Exception {
		Consulta a = preparar();
		Consulta b = preparar();

		consentir(a, "lixo", true, "v1").andExpect(status().isForbidden());
		consentir(a, b.entrada(), true, "v1").andExpect(status().isForbidden());
		mvc.perform(post("/api/consultas/" + UUID.randomUUID() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + a.entrada() + "\",\"aceito\":true,\"versaoTermo\":\"v1\"}")).andExpect(status().isForbidden());
		detalhe(a).andExpect(jsonPath("$.consentimentoEm").doesNotExist());
		detalhe(b).andExpect(jsonPath("$.consentimentoEm").doesNotExist());
	}

	@Test
	void medicoSemTokenDoPacienteNaoConsenteEmNomeDele() throws Exception {
		Consulta c = preparar();

		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"aceito\":true,\"versaoTermo\":\"v1\"}")).andExpect(status().isForbidden());
		detalhe(c).andExpect(jsonPath("$.consentimentoEm").doesNotExist());
	}

	@Test
	void consultaEncerradaNaoAceitaMaisConsentimento() throws Exception {
		Consulta c = preparar();
		jdbc.update("UPDATE consulta SET status = 'ENCERRADA' WHERE id = ?", UUID.fromString(c.id()));

		consentir(c, c.entrada(), true, "v1").andExpect(status().isConflict());
	}

	@Test
	void respostaAoPacienteNaoVazaDadosDaConsulta() throws Exception {
		Consulta c = preparar();

		consentir(c, c.entrada(), true, "v1").andExpect(content().json("{\"consentimentoVigente\":true,\"versaoDoTermo\":\"v1\"}", true));
	}
}
