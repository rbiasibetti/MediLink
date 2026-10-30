package br.com.medilink.integracao;

import br.com.medilink.domain.ApiKey;
import br.com.medilink.repository.ApiKeyRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.IntegradorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T16: só entra quem porta uma key válida, e a key não serve para mais nada além da API de integração. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "medilink.integracao.limite-por-minuto=5")
class ApiKeyAutenticacaoTest {

	private static final String ROTA = "/api/integracao/v1/agendamentos/" + UUID.randomUUID();

	@Autowired MockMvc mvc;
	@Autowired IntegradorRepository integradores;
	@Autowired ApiKeyRepository keyRepo;
	@Autowired ApiKeyService keys;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private SistemaIntegrado sistema;

	@BeforeEach
	void montar() {
		sistema = new SistemaIntegrado(integradores, keys);
	}

	@Test
	void keyValidaPassaEOsDemaisCasosDaoSempreOMesmo401() throws Exception {
		mvc.perform(get(ROTA).header("X-API-Key", sistema.chave())).andExpect(status().isNotFound()); // autenticou; a reunião não existe

		String valida = sistema.chave();
		String[] ruins = {
				null,
				"",
				"lixo",
				"mlk_",
				"mlk_abc_def",
				valida.substring(0, valida.length() - 1) + (valida.endsWith("A") ? "B" : "A"),   // segredo errado
				valida.replaceFirst("mlk_[0-9a-f]{32}", "mlk_" + UUID.randomUUID().toString().replace("-", "")), // key inexistente
				valida + "x",
		};
		for (String ruim : ruins) {
			var req = get(ROTA);
			if (ruim != null) {
				req = req.header("X-API-Key", ruim);
			}
			mvc.perform(req).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "ApiKey"));
		}
	}

	@Test
	void keyRevogadaVencidaOuDeSistemaDesativadoNaoPassam() throws Exception {
		var revogada = new SistemaIntegrado(integradores, keys);
		keys.revogar("teste", revogada.key.keyId());
		mvc.perform(get(ROTA).header("X-API-Key", revogada.chave())).andExpect(status().isUnauthorized());

		var vencida = new SistemaIntegrado(integradores, keys);
		jdbc.update("UPDATE api_key SET expira_em = ? WHERE id = ?", Instant.now().minus(1, ChronoUnit.HOURS).atOffset(java.time.ZoneOffset.UTC), vencida.key.keyId());
		mvc.perform(get(ROTA).header("X-API-Key", vencida.chave())).andExpect(status().isUnauthorized());

		var desativado = new SistemaIntegrado(integradores, keys);
		jdbc.update("UPDATE integrador SET ativo = 0 WHERE id = ?", desativado.integrador.getId());
		mvc.perform(get(ROTA).header("X-API-Key", desativado.chave())).andExpect(status().isUnauthorized());

		assertThat(auditoria.findByRecursoOrderByMomento("api-key/" + revogada.key.keyId())).extracting("acao")
				.contains("API_KEY_CRIADA", "API_KEY_REVOGADA");
	}

	@Test
	void oSegredoNaoFicaGuardadoSoOHash() {
		ApiKey k = keyRepo.findById(sistema.key.keyId()).orElseThrow();
		String segredo = sistema.chave().substring("mlk_".length() + 32 + 1); // o segredo em Base64 URL pode ter "_"

		assertThat(k.getHashSegredo()).hasSize(64).isNotEqualTo(segredo).isEqualTo(ApiKeyService.hash(segredo));
		assertThat(jdbc.queryForObject("SELECT hash_segredo FROM api_key WHERE id = ?", String.class, k.getId())).doesNotContain(segredo);
		assertThat(sistema.key.toString()).doesNotContain(segredo);
	}

	@Test
	void escoposInvalidosOuVaziosSaoRecusados() {
		var i = new SistemaIntegrado(integradores, keys).integrador;

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.criar("t", i.getId(), "x", Set.of("admin:tudo"), null))
				.hasMessageContaining("Escopo inválido");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> keys.criar("t", i.getId(), "x", Set.of(), null))
				.hasMessageContaining("Escopo inválido");
	}

	@Test
	void passarDoLimiteDeUsoDa429() throws Exception {
		for (int i = 0; i < 5; i++) {
			mvc.perform(get(ROTA).header("X-API-Key", sistema.chave())).andExpect(status().isNotFound());
		}

		mvc.perform(get(ROTA).header("X-API-Key", sistema.chave())).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
		// Outra key não sofre com o limite desta.
		mvc.perform(get(ROTA).header("X-API-Key", new SistemaIntegrado(integradores, keys).chave())).andExpect(status().isNotFound());
	}

	@Test
	void aKeyNaoServeNosEndpointsDoMedicoENemOLoginDoMedicoNaApiDeIntegracao() throws Exception {
		// O cabeçalho é ignorado fora de /api/integracao: sem login de médico, é 401.
		mvc.perform(get("/api/me").header("X-API-Key", sistema.chave())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + UUID.randomUUID()).header("X-API-Key", sistema.chave())).andExpect(status().isUnauthorized());
		// E um médico autenticado não ganha acesso à API de integração.
		mvc.perform(get(ROTA).with(user("medico@x.test"))).andExpect(status().isUnauthorized());
	}
}
