package br.com.medilink.admin;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.integracao.SistemaIntegrado;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.transcricao.PublicadorDeJobs;
import br.com.medilink.transcricao.TranscricaoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static br.com.medilink.admin.AdminTeste.bearer;
import static br.com.medilink.admin.AdminTeste.campo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T18: gestão de sistemas integrados, API keys, webhook, reenvio de job e painel (sem dado de paciente). */
@SpringBootTest
@AutoConfigureMockMvc
class AdminGestaoApiTest {

	@Autowired MockMvc mvc;
	@Autowired AdminAuthService auth;
	@Autowired br.com.medilink.integracao.ApiKeyService keys;
	@Autowired IntegradorRepository integradores;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService jobsService;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean PublicadorDeJobs publicador;

	private String h; // cabeçalho Authorization do administrador

	@BeforeEach
	void montar() throws Exception {
		h = bearer(AdminTeste.token(mvc, auth));
	}

	private String novoIntegrador() throws Exception {
		return campo(mvc.perform(post("/api/admin/integradores").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nome\":\"Sistema " + UUID.randomUUID() + "\"}")).andExpect(status().isCreated()), "id");
	}

	private String novaKey(String integrador, String escopos) throws Exception {
		return campo(mvc.perform(post("/api/admin/integradores/" + integrador + "/chaves").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nome\":\"producao\",\"escopos\":" + escopos + "}")).andExpect(status().isCreated()), "chave");
	}

	@Test
	void criaSistemaEKeyQueFuncionaNaApiDeIntegracaoESoApareceUmaVez() throws Exception {
		String integrador = novoIntegrador();
		String chave = novaKey(integrador, "[\"agendamento:ler\"]");

		mvc.perform(get("/api/integracao/v1/agendamentos/" + UUID.randomUUID()).header("X-API-Key", chave)).andExpect(status().isNotFound()); // autenticou
		String listagem = mvc.perform(get("/api/admin/integradores/" + integrador + "/chaves").header("Authorization", h)).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].escopos").value("agendamento:ler")).andReturn().getResponse().getContentAsString();
		assertThat(listagem).doesNotContain(chave).doesNotContain(chave.substring(chave.lastIndexOf('_') + 1)).doesNotContain("hash");
		assertThat(auditoria.findAll().stream().filter(a -> a.getAcao().equals("API_KEY_CRIADA")).count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void revogarAKeyOuDesativarOSistemaCortaOAcesso() throws Exception {
		String integrador = novoIntegrador();
		String chave = novaKey(integrador, "[\"agendamento:ler\"]");
		String keyId = campo(mvc.perform(get("/api/admin/integradores/" + integrador + "/chaves").header("Authorization", h)), "id");

		mvc.perform(post("/api/admin/chaves/" + keyId + "/revogar").header("Authorization", h)).andExpect(status().isNoContent());
		mvc.perform(get("/api/integracao/v1/agendamentos/" + UUID.randomUUID()).header("X-API-Key", chave)).andExpect(status().isUnauthorized());

		String outraChave = novaKey(integrador, "[\"agendamento:ler\"]");
		mvc.perform(patch("/api/admin/integradores/" + integrador).header("Authorization", h).contentType(MediaType.APPLICATION_JSON).content("{\"ativo\":false}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.ativo").value(false));
		mvc.perform(get("/api/integracao/v1/agendamentos/" + UUID.randomUUID()).header("X-API-Key", outraChave)).andExpect(status().isUnauthorized());
	}

	@Test
	void escopoInvalidoESistemaInexistenteSaoRecusados() throws Exception {
		String integrador = novoIntegrador();
		mvc.perform(post("/api/admin/integradores/" + integrador + "/chaves").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nome\":\"x\",\"escopos\":[\"admin:tudo\"]}")).andExpect(status().isUnprocessableEntity());
		mvc.perform(post("/api/admin/integradores/" + UUID.randomUUID() + "/chaves").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nome\":\"x\",\"escopos\":[\"agendamento:ler\"]}")).andExpect(status().isNotFound());
	}

	@Test
	void webhookValidaABaseEMostraOSegredoSoQuandoGera() throws Exception {
		String integrador = novoIntegrador();
		for (String ruim : new String[] {"http://cliente.test/hooks", "https://user:senha@cliente.test/h", "https://cliente.test/h?x=1", "https://cliente.test/h#f",
				"https://cliente.test/../x", "ftp://cliente.test/h", "nao-e-url"}) {
			mvc.perform(put("/api/admin/integradores/" + integrador + "/webhook").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
					.content("{\"baseUrl\":\"" + ruim + "\",\"ativo\":true}")).andExpect(status().isUnprocessableEntity());
		}
		mvc.perform(put("/api/admin/integradores/" + integrador + "/webhook").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"ativo\":true}")).andExpect(status().isUnprocessableEntity());

		String primeiro = campo(mvc.perform(put("/api/admin/integradores/" + integrador + "/webhook").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"baseUrl\":\"https://cliente.test/hooks/medilink\",\"ativo\":true}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.integrador.webhookBaseUrl").value("https://cliente.test/hooks/medilink")), "segredo");
		assertThat(primeiro).startsWith("whsec_");
		mvc.perform(put("/api/admin/integradores/" + integrador + "/webhook").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"baseUrl\":\"https://cliente.test/hooks/medilink\",\"ativo\":true}")).andExpect(jsonPath("$.segredo").doesNotExist());
		String novo = campo(mvc.perform(put("/api/admin/integradores/" + integrador + "/webhook").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"baseUrl\":\"https://cliente.test/hooks/medilink\",\"ativo\":true,\"novoSegredo\":true}")), "segredo");
		assertThat(novo).isNotEqualTo(primeiro);
		assertThat(jdbc.queryForObject("SELECT webhook_segredo FROM integrador WHERE id = ?", String.class, UUID.fromString(integrador)))
				.startsWith("enc:").doesNotContain(novo);
		mvc.perform(get("/api/admin/integradores").header("Authorization", h)).andExpect(content().string(not(containsString("whsec_"))));
	}

	@Test
	void jobQueFalhouPodeSerReenviadoSoSeOAudioAindaExiste() throws Exception {
		var cenario = new CenarioDeConsulta(mvc, medicos);
		var c = cenario.criar();
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		UUID consultaId = UUID.fromString(c.id());
		jdbc.update("UPDATE transcricao_job SET status = 'FALHA_NO_WORKER', tentativas = 7, ultimo_erro = 'RuntimeError' WHERE consulta_id = ?", consultaId);

		mvc.perform(post("/api/admin/jobs/" + consultaId + "/reenviar").header("Authorization", h)).andExpect(status().isConflict()); // sem áudio

		AudioTransitorio a = new AudioTransitorio(consultaId, "med-a0", "med00001", c.id() + "/med00001-1.opus.enc", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		audios.save(a);
		mvc.perform(post("/api/admin/jobs/" + consultaId + "/reenviar").header("Authorization", h)).andExpect(status().isNoContent());

		var job = jobs.findByConsultaId(consultaId).orElseThrow();
		assertThat(job.getStatus().name()).isEqualTo("PENDENTE");
		assertThat(job.getTentativas()).isZero();
		assertThat(job.getUltimoErro()).isNull();
		mvc.perform(post("/api/admin/jobs/" + consultaId + "/reenviar").header("Authorization", h)).andExpect(status().isConflict()); // já não está falho
		mvc.perform(post("/api/admin/jobs/" + UUID.randomUUID() + "/reenviar").header("Authorization", h)).andExpect(status().isNotFound());
	}

	@Test
	void painelMostraContagensEIncidentesSemNenhumDadoDePaciente() throws Exception {
		var sistema = new SistemaIntegrado(integradores, keys);
		var cenario = new CenarioDeConsulta(mvc, medicos);
		var c = cenario.criar();
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		jobsService.publicarPendentes(); // sem áudio: SEM_AUDIO, um incidente

		String json = mvc.perform(get("/api/admin/painel?dias=7").header("Authorization", h)).andExpect(status().isOk())
				.andExpect(jsonPath("$.agendamentos.porStatus.AGENDADO").exists())
				.andExpect(jsonPath("$.consultas.porStatus").exists())
				.andExpect(jsonPath("$.transcricao.jobsPorStatus.SEM_AUDIO").exists())
				.andExpect(jsonPath("$.incidentes[?(@.consultaId == '" + c.id() + "')].tipo").value("SEM_AUDIO"))
				.andReturn().getResponse().getContentAsString();

		assertThat(json).doesNotContain("Maria Souza").doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("@clinica.test")
				.doesNotContain("enc:").doesNotContain(cenario.emailMedico);
		assertThat(auditoria.findAll().stream().filter(a -> a.getAcao().equals("PAINEL_ACESSADO")).count()).isGreaterThanOrEqualTo(1);
		assertThat(sistema.integrador.getNome()).isNotBlank();
	}
}
