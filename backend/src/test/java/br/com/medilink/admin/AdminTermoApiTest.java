package br.com.medilink.admin;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.service.TermoConsentimento;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static br.com.medilink.admin.AdminTeste.bearer;
import static br.com.medilink.admin.AdminTeste.campo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T18: o termo evolui por versões imutáveis. Transacional de propósito: publicar muda o termo vigente de TODA a base de
 * testes, e o rollback no fim devolve o v2 para os outros testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTermoApiTest {

	private static final String TEXTO = "Termo de teste. ".repeat(20);

	@Autowired MockMvc mvc;
	@Autowired AdminAuthService auth;
	@Autowired MedicoRepository medicos;
	@Autowired TermoConsentimento vigente;
	@Autowired JdbcTemplate jdbc;
	@jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

	private String h;

	@BeforeEach
	void montar() throws Exception {
		h = bearer(AdminTeste.token(mvc, auth));
		vigente.invalidar();
	}

	@AfterEach
	void limpar() {
		vigente.invalidar(); // o rollback desfaz o banco; o cache em memória precisa acompanhar
	}

	private ResultActions rascunho(String texto) throws Exception {
		return mvc.perform(post("/api/admin/termos").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + texto.replace("\n", "\\n") + "\"}"));
	}

	@Test
	void osTermosV1eV2VieramDosArquivosEOV2EOVigente() throws Exception {
		mvc.perform(get("/api/termo-consentimento")).andExpect(status().isOk()).andExpect(jsonPath("$.versao").value("v2"));
		mvc.perform(get("/api/admin/termos").header("Authorization", h)).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.versao == 'v1')].status").value("ARQUIVADO")).andExpect(jsonPath("$[?(@.versao == 'v2')].status").value("VIGENTE"));
	}

	@Test
	void rascunhoEditaPublicaEViraOTermoDosPacientes() throws Exception {
		ResultActions r = rascunho(TEXTO).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("RASCUNHO"));
		String id = campo(r, "id");
		String versao = campo(r, "versao");
		assertThat(versao).matches("v\\d+").isNotEqualTo("v2");
		mvc.perform(get("/api/termo-consentimento")).andExpect(jsonPath("$.versao").value("v2")); // rascunho não vale

		String novoTexto = "Texto revisado do termo, agora com a transcrição enviada ao sistema da clínica. ".repeat(3);
		mvc.perform(put("/api/admin/termos/" + id).header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + novoTexto + "\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/admin/termos/" + id + "/publicar").header("Authorization", h)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("VIGENTE"));

		mvc.perform(get("/api/termo-consentimento")).andExpect(jsonPath("$.versao").value(versao))
				.andExpect(jsonPath("$.texto").value(novoTexto.strip()));
		mvc.perform(get("/api/admin/termos").header("Authorization", h))
				.andExpect(jsonPath("$[?(@.versao == 'v2')].status").value("ARQUIVADO"));   // o anterior foi arquivado
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM termo_consentimento WHERE status = 'VIGENTE'", Integer.class)).isEqualTo(1);
	}

	@Test
	void versaoPublicadaEImutavelEPublicarDuasVezesOuEditarDao409() throws Exception {
		String id = campo(rascunho(TEXTO).andExpect(status().isCreated()), "id");
		mvc.perform(post("/api/admin/termos/" + id + "/publicar").header("Authorization", h)).andExpect(status().isOk());

		mvc.perform(put("/api/admin/termos/" + id).header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + TEXTO + "alterado\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/admin/termos/" + id + "/publicar").header("Authorization", h)).andExpect(status().isConflict());
		mvc.perform(delete("/api/admin/termos/" + id).header("Authorization", h)).andExpect(status().isConflict());
		String arquivado = jdbc.queryForObject("SELECT CAST(id AS VARCHAR(36)) FROM termo_consentimento WHERE versao = 'v1'", String.class);
		mvc.perform(post("/api/admin/termos/" + arquivado + "/publicar").header("Authorization", h)).andExpect(status().isConflict()); // arquivado não volta
	}

	@Test
	void rascunhoPodeSerExcluido() throws Exception {
		String id = campo(rascunho(TEXTO).andExpect(status().isCreated()), "id");

		mvc.perform(delete("/api/admin/termos/" + id).header("Authorization", h)).andExpect(status().isNoContent());
		mvc.perform(delete("/api/admin/termos/" + id).header("Authorization", h)).andExpect(status().isNotFound());
	}

	@Test
	void validaTamanhoECaracteresDoTexto() throws Exception {
		rascunho("curto demais").andExpect(status().isUnprocessableEntity());
		rascunho("x".repeat(20_001)).andExpect(status().isUnprocessableEntity());
		mvc.perform(post("/api/admin/termos").header("Authorization", h).contentType(MediaType.APPLICATION_JSON)
				.content("{\"texto\":\"" + TEXTO + "\\u0000\\u0007\"}")).andExpect(status().isUnprocessableEntity());
		// HTML não é interpretado: é guardado como texto e a tela mostra como texto.
		rascunho(TEXTO + "<script>alert(1)</script>").andExpect(status().isCreated()).andExpect(jsonPath("$.texto").value((TEXTO + "<script>alert(1)</script>").strip()));
	}

	@Test
	void oConsentimentoGravaAVersaoEOHashDoTextoQueOPacienteViu() throws Exception {
		String id = campo(rascunho(TEXTO).andExpect(status().isCreated()), "id");
		String versao = campo(mvc.perform(post("/api/admin/termos/" + id + "/publicar").header("Authorization", h)), "versao");
		var cenario = new CenarioDeConsulta(mvc, medicos);
		var c = cenario.criar();

		// O termo antigo deixou de valer: quem leu o v2 precisa ler a versão nova.
		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\",\"aceito\":true,\"versaoTermo\":\"v2\"}")).andExpect(status().isConflict());
		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\",\"aceito\":true,\"versaoTermo\":\"" + versao + "\"}")).andExpect(status().isOk());

		em.flush(); // o teste é transacional: grava o que o serviço alterou antes de ler pelo JDBC
		var linha = jdbc.queryForMap("SELECT consentimento_versao, consentimento_hash FROM consulta WHERE id = ?", UUID.fromString(c.id()));
		assertThat(linha.get("consentimento_versao")).isEqualTo(versao);
		assertThat((String) linha.get("consentimento_hash")).hasSize(64).isEqualTo(TermoService.hash(TEXTO.strip()));
	}

	@Test
	void semSessaoDeAdminNaoMexeNoTermo() throws Exception {
		mvc.perform(post("/api/admin/termos").contentType(MediaType.APPLICATION_JSON).content("{\"texto\":\"" + TEXTO + "\"}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/admin/termos/" + UUID.randomUUID() + "/publicar")).andExpect(status().isUnauthorized());
	}
}
