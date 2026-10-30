package br.com.medilink.medico;

import br.com.medilink.integracao.ApiKeyService;
import br.com.medilink.integracao.SistemaIntegrado;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.service.ConsultaService;
import br.com.medilink.transcricao.TranscricaoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T17: o médico entra por link de uso único e fica com uma sessão presa à consulta; reabertura e inatividade. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "medilink.consulta.tolerancia-reabertura=10m")
class AcessoMedicoApiTest {

	private static final String BASE = "/api/integracao/v1/agendamentos";
	private static final String CPF = "529.982.247-25";

	@Autowired MockMvc mvc;
	@Autowired IntegradorRepository integradores;
	@Autowired ApiKeyService keys;
	@Autowired JdbcTemplate jdbc;
	@Autowired ConsultaService consultaService;
	@Autowired TranscricaoJobService jobsService;
	@Autowired TranscricaoJobRepository jobs;

	private SistemaIntegrado sistema;
	private String chave;      // chave da reunião
	private String sala;       // roomName (do link do paciente)
	private UUID consultaId;

	@BeforeEach
	void montar() throws Exception {
		sistema = new SistemaIntegrado(integradores, keys);
		criarReuniao(Instant.now().plus(10, ChronoUnit.MINUTES));
	}

	private void criarReuniao(Instant quando) throws Exception {
		String json = """
				{"medico":{"nome":"Dra. Ana Lima","chave":"CRM-1"},"agendamento":{"chave":"r-%s","quando":{"dataHora":"%s"}},
				 "paciente":{"nome":"Maria Souza","cpf":"%s"}}""".formatted(UUID.randomUUID(), quando.atOffset(ZoneOffset.UTC), CPF);
		ResultActions r = mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated());
		chave = campo(r, "chaveReuniao");
		sala = campo(r, "linkPaciente").replaceAll(".*/sala/", "");
		consultaId = jdbc.queryForObject("SELECT id FROM consulta WHERE agendamento_id = ?", UUID.class, UUID.fromString(chave));
	}

	private ResultActions pedirLink(String medicoChave) throws Exception {
		return mvc.perform(post(BASE + "/" + chave + "/acesso-medico").header("X-API-Key", sistema.chave())
				.contentType(MediaType.APPLICATION_JSON).content("{\"nomeMedico\":\"Dra. Ana Lima\",\"chaveMedico\":\"" + medicoChave + "\"}"));
	}

	private static String tokenDe(ResultActions r) throws Exception {
		return campo(r, "linkMedico").replaceAll(".*#t=", "");
	}

	private ResultActions trocar(String token) throws Exception {
		return mvc.perform(post("/api/medico/sessao").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
	}

	/** Pede o link e já troca por uma sessão (Bearer). */
	private String sessao() throws Exception {
		return campo(trocar(tokenDe(pedirLink("CRM-1").andExpect(status().isOk()))).andExpect(status().isOk()), "sessao");
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		String json = r.andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", nome, json).isTrue();
		return m.group(1);
	}

	private static String bearer(String sessao) {
		return "Bearer " + sessao;
	}

	/** O paciente consente, espera e o médico (pela sessão) o aceita: consulta em andamento. */
	private void iniciarConsulta(String sessao) throws Exception {
		String entrada = campo(mvc.perform(post("/api/salas/" + sala + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + CPF + "\"}")).andExpect(status().isOk()), "tokenEntrada");
		mvc.perform(post("/api/consultas/" + consultaId + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + entrada + "\",\"aceito\":true,\"versaoTermo\":\"v2\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/salas/" + sala + "/token").contentType(MediaType.APPLICATION_JSON).content("{\"tokenEntrada\":\"" + entrada + "\"}"))
				.andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + consultaId + "/aceite").header("Authorization", bearer(sessao))).andExpect(status().isOk());
	}

	@Test
	void linkDeUsoUnicoViraSessaoDaConsultaQueNaoDaAcessoAoRestante() throws Exception {
		ResultActions r = pedirLink("CRM-1").andExpect(status().isOk()).andExpect(jsonPath("$.modo").value("SALA"))
				.andExpect(jsonPath("$.linkMedico").value(containsString("/medico/acesso#t=")))
				.andExpect(content().string(not(containsString("eyJ")))); // nenhum JWT (nem o do Jitsi) sai para o sistema integrado
		String token = tokenDe(r);

		String sessao = campo(trocar(token).andExpect(status().isOk()).andExpect(jsonPath("$.modo").value("SALA"))
				.andExpect(jsonPath("$.consultaId").value(consultaId.toString())).andExpect(jsonPath("$.medicoNome").value("Dra. Ana Lima")), "sessao");

		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(sessao))).andExpect(status().isOk());
		mvc.perform(get("/api/me").header("Authorization", bearer(sessao))).andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Dra. Ana Lima"));
		mvc.perform(post("/api/agendamentos/" + chave + "/token-jitsi").header("Authorization", bearer(sessao)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.jwt").exists());
		// Nada além da própria consulta.
		mvc.perform(get("/api/consultas/" + UUID.randomUUID()).header("Authorization", bearer(sessao))).andExpect(status().isForbidden());
		mvc.perform(post("/api/agendamentos/" + UUID.randomUUID() + "/token-jitsi").header("Authorization", bearer(sessao))).andExpect(status().isForbidden());
		mvc.perform(post("/api/agendamentos").header("Authorization", bearer(sessao)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/agendamentos/" + chave + "/enviar-email").header("Authorization", bearer(sessao))).andExpect(status().isForbidden());
	}

	@Test
	void sessaoNaoServeComAssinaturaRuimNemSemTokenENemOTokenDoLink() throws Exception {
		String sessao = sessao();
		String adulterada = sessao.substring(0, sessao.length() - 3) + (sessao.endsWith("AAA") ? "BBB" : "AAA");

		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(adulterada))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + consultaId)).andExpect(status().isUnauthorized());
		String token = tokenDe(pedirLink("CRM-1").andExpect(status().isOk()));
		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
	}

	@Test
	void oLinkSoSeUsaUmaVezVenceERevogadoPeloSeguinte() throws Exception {
		String token = tokenDe(pedirLink("CRM-1").andExpect(status().isOk()));
		trocar(token).andExpect(status().isOk());
		trocar(token).andExpect(status().isForbidden());                      // usado

		String vencido = tokenDe(pedirLink("CRM-1"));
		jdbc.update("UPDATE acesso_medico_link SET expira_em = ? WHERE consulta_id = ? AND usado_em IS NULL AND revogado_em IS NULL",
				Instant.now().minusSeconds(5).atOffset(ZoneOffset.UTC), consultaId);
		trocar(vencido).andExpect(status().isForbidden());                    // vencido

		String primeiro = tokenDe(pedirLink("CRM-1"));
		String segundo = tokenDe(pedirLink("CRM-1"));
		trocar(primeiro).andExpect(status().isForbidden());                   // o último pedido invalida o anterior
		trocar(segundo).andExpect(status().isOk());

		trocar("x".repeat(43)).andExpect(status().isForbidden());             // inexistente: mesma resposta
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acesso_medico_link WHERE hash_token LIKE ?", Integer.class, "%" + segundo + "%")).isZero(); // só hash
	}

	@Test
	void trocaConcorrenteDoMesmoLinkSoDaUmaSessao() throws Exception {
		String token = tokenDe(pedirLink("CRM-1").andExpect(status().isOk()));
		var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
		try {
			var tarefas = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 6; i++) {
				tarefas.add(pool.submit(() -> trocar(token).andReturn().getResponse().getStatus()));
			}
			int ok = 0;
			for (var t : tarefas) {
				if (t.get() == 200) {
					ok++;
				}
			}
			assertThat(ok).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void medicoDiferenteOutroSistemaEscopoECanceladaSaoNegados() throws Exception {
		pedirLink("OUTRO-CRM").andExpect(status().isForbidden());
		var outro = new SistemaIntegrado(integradores, keys);
		mvc.perform(post(BASE + "/" + chave + "/acesso-medico").header("X-API-Key", outro.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nomeMedico\":\"Dra. Ana Lima\",\"chaveMedico\":\"CRM-1\"}")).andExpect(status().isNotFound());
		var soLeitura = new SistemaIntegrado(integradores, keys, java.util.Set.of("agendamento:ler"));
		mvc.perform(post(BASE + "/" + chave + "/acesso-medico").header("X-API-Key", soLeitura.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nomeMedico\":\"Dra. Ana Lima\",\"chaveMedico\":\"CRM-1\"}")).andExpect(status().isForbidden()); // falta o escopo sala:iniciar

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE + "/" + chave).header("X-API-Key", sistema.chave()))
				.andExpect(status().isNoContent());
		pedirLink("CRM-1").andExpect(status().isConflict());
	}

	@Test
	void soPodeIniciarNaJanelaDoHorario() throws Exception {
		criarReuniao(Instant.now().plus(5, ChronoUnit.DAYS));
		pedirLink("CRM-1").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("Cedo demais")));

		jdbc.update("UPDATE agendamento SET data_hora = ? WHERE id = ?", Instant.now().minus(20, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC), UUID.fromString(chave));
		pedirLink("CRM-1").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("expirou")));

		jdbc.update("UPDATE agendamento SET data_hora = ? WHERE id = ?", Instant.now().plus(20, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), UUID.fromString(chave));
		pedirLink("CRM-1").andExpect(status().isOk());
	}

	@Test
	void consultaEmAndamentoPodeSerRetomadaForaDaJanelaESempreComNovoLink() throws Exception {
		String sessao = sessao();
		iniciarConsulta(sessao);
		jdbc.update("UPDATE agendamento SET data_hora = ? WHERE id = ?", Instant.now().minus(3, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), UUID.fromString(chave));

		pedirLink("CRM-1").andExpect(status().isOk()).andExpect(jsonPath("$.modo").value("SALA")); // voltando durante a consulta
	}

	@Test
	void encerradaDentroDaToleranciaPodeReabrirEDepoisSoLeitura() throws Exception {
		String sessao = sessao();
		iniciarConsulta(sessao);
		mvc.perform(post("/api/consultas/" + consultaId + "/encerrar").header("Authorization", bearer(sessao))).andExpect(status().isOk());
		assertThat(jobsService.publicarPendentes()).isZero(); // dentro da tolerância o job ainda não é publicado

		String nova = campo(trocar(tokenDe(pedirLink("CRM-1").andExpect(status().isOk()).andExpect(jsonPath("$.modo").value("SALA")))).andExpect(status().isOk()), "sessao");
		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(nova))).andExpect(jsonPath("$.podeReabrir").value(true));
		mvc.perform(post("/api/consultas/" + consultaId + "/reabrir").header("Authorization", bearer(nova)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_ANDAMENTO")).andExpect(jsonPath("$.capturaAutorizada").value(true));
		assertThat(jobs.findByConsultaId(consultaId)).isEmpty();   // reabrir cancela o job

		mvc.perform(post("/api/consultas/" + consultaId + "/encerrar").header("Authorization", bearer(nova))).andExpect(status().isOk());
		jdbc.update("UPDATE consulta SET fim_em = ? WHERE id = ?", Instant.now().minus(30, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), consultaId);
		mvc.perform(post("/api/consultas/" + consultaId + "/reabrir").header("Authorization", bearer(nova))).andExpect(status().isConflict()); // passou da tolerância

		String leitura = campo(trocar(tokenDe(pedirLink("CRM-1").andExpect(status().isOk()).andExpect(jsonPath("$.modo").value("LEITURA")))).andExpect(status().isOk()), "sessao");
		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(leitura))).andExpect(status().isOk());
		mvc.perform(get("/api/consultas/" + consultaId + "/transcricao").header("Authorization", bearer(leitura))).andExpect(status().is2xxSuccessful());
		mvc.perform(post("/api/consultas/" + consultaId + "/aceite").header("Authorization", bearer(leitura))).andExpect(status().isForbidden());
		mvc.perform(post("/api/consultas/" + consultaId + "/reabrir").header("Authorization", bearer(leitura))).andExpect(status().isForbidden());
		mvc.perform(post("/api/agendamentos/" + chave + "/token-jitsi").header("Authorization", bearer(leitura))).andExpect(status().isForbidden());
	}

	@Test
	void medicoQueSomeTemAConsultaEncerradaPeloServidor() throws Exception {
		String sessao = sessao();
		iniciarConsulta(sessao);
		consultaService.encerrarInativas();
		assertThat(jdbc.queryForObject("SELECT status FROM consulta WHERE id = ?", String.class, consultaId)).isEqualTo("EM_ANDAMENTO"); // acabou de dar sinal

		jdbc.update("UPDATE consulta SET ultima_presenca_medico_em = ? WHERE id = ?", Instant.now().minus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), consultaId);
		assertThat(consultaService.encerrarInativas()).isGreaterThanOrEqualTo(1);

		assertThat(jdbc.queryForObject("SELECT status FROM consulta WHERE id = ?", String.class, consultaId)).isEqualTo("ENCERRADA");
		assertThat(jobs.findByConsultaId(consultaId)).isPresent();
		String nova = campo(trocar(tokenDe(pedirLink("CRM-1").andExpect(status().isOk()))).andExpect(status().isOk()), "sessao");
		mvc.perform(post("/api/consultas/" + consultaId + "/reabrir").header("Authorization", bearer(nova))).andExpect(status().isOk());
	}

	@Test
	void cadaConsultaDoMedicoNaTelaRenovaOSinalDeVida() throws Exception {
		String sessao = sessao();
		iniciarConsulta(sessao);
		jdbc.update("UPDATE consulta SET ultima_presenca_medico_em = ? WHERE id = ?", Instant.now().minus(4, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), consultaId);

		mvc.perform(get("/api/consultas/" + consultaId).header("Authorization", bearer(sessao))).andExpect(status().isOk());

		Instant presenca = jdbc.queryForObject("SELECT ultima_presenca_medico_em FROM consulta WHERE id = ?", java.time.OffsetDateTime.class, consultaId).toInstant();
		assertThat(presenca).isAfter(Instant.now().minusSeconds(30));
	}

	@Test
	void linkDoPacienteVenceDepoisDoHorarioMasNaoNoMeioDaConsulta() throws Exception {
		mvc.perform(get("/api/salas/" + sala + "/acesso")).andExpect(status().isOk());
		jdbc.update("UPDATE agendamento SET data_hora = ? WHERE id = ?", Instant.now().minus(30, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC), UUID.fromString(chave));
		mvc.perform(get("/api/salas/" + sala + "/acesso")).andExpect(status().isNotFound());
		mvc.perform(post("/api/salas/" + sala + "/validar-cpf").contentType(MediaType.APPLICATION_JSON).content("{\"cpf\":\"" + CPF + "\"}"))
				.andExpect(status().isForbidden());

		jdbc.update("UPDATE consulta SET status = 'EM_ANDAMENTO' WHERE id = ?", consultaId); // já começou: não vence pelo relógio
		mvc.perform(get("/api/salas/" + sala + "/acesso")).andExpect(status().isOk());
	}

	@Test
	void linksEmExcessoPorReuniaoDao429() throws Exception {
		for (int i = 0; i < 10; i++) {
			pedirLink("CRM-1").andExpect(status().isOk());
		}
		pedirLink("CRM-1").andExpect(status().isTooManyRequests());
	}
}
