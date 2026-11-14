package br.com.medilink.web;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.medico.SessaoMedicoService;
import br.com.medilink.domain.AcessoMedicoLink.Modo;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.transcricao.PublicadorDeJobs;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService;
import br.com.medilink.transcricao.TranscricaoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T20: {@code GET /api/consultas}: o médico vê só as próprias consultas e a situação da transcrição de cada uma. */
@SpringBootTest
@AutoConfigureMockMvc
class HistoricoDeConsultasApiTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService jobsService;
	@Autowired ResultadoDaTranscricaoService resultados;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired AudioTransitorioRepository audios;
	@Autowired SessaoMedicoService sessoes;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean PublicadorDeJobs publicador;

	private CenarioDeConsulta cenario;

	@BeforeEach
	void montar() {
		cenario = new CenarioDeConsulta(mvc, medicos);
	}

	private Consulta encerradaComAudio() throws Exception {
		Consulta c = cenario.criar();
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		AudioTransitorio a = new AudioTransitorio(UUID.fromString(c.id()), "med-a0", "med00001", c.id() + "/med00001-1.opus", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		audios.save(a);
		jobsService.publicarPendentes();
		return c;
	}

	@Test
	void listaSoAsConsultasDoMedicoComEstadoDaTranscricao() throws Exception {
		Consulta aberta = cenario.criar();
		Consulta processando = encerradaComAudio();
		Consulta pronta = encerradaComAudio();
		UUID jobId = jobs.findByConsultaId(UUID.fromString(pronta.id())).orElseThrow().getId();
		resultados.processar("""
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":9000,
				 "segmentos":[{"papel":"MEDICO","inicioMs":100,"fimMs":2000,"texto":"Bom dia."}]}
				""".formatted(jobId, pronta.id()));
		var outro = new CenarioDeConsulta(mvc, medicos);
		outro.criar();

		mvc.perform(get("/api/consultas").with(user(cenario.emailMedico)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.leituraDeTranscricao").value(true))
				.andExpect(jsonPath("$.itens", hasSize(3)))
				.andExpect(jsonPath("$.itens[?(@.consultaId=='%s')].transcricao".formatted(aberta.id())).value("NAO_APLICAVEL"))
				.andExpect(jsonPath("$.itens[?(@.consultaId=='%s')].transcricao".formatted(processando.id())).value("EM_PROCESSAMENTO"))
				.andExpect(jsonPath("$.itens[?(@.consultaId=='%s')].transcricao".formatted(pronta.id())).value("DISPONIVEL"))
				.andExpect(jsonPath("$.itens[?(@.consultaId=='%s')].pacienteNome".formatted(pronta.id())).value("Maria Souza"))
				.andExpect(content().string(not(containsString("529.982.247-25"))))
				.andExpect(content().string(not(containsString("tokenEntrada"))))
				.andExpect(content().string(not(containsString("Bom dia"))));   // a lista nunca carrega o texto da transcrição
	}

	@Test
	void filtraPorStatusEPagina() throws Exception {
		cenario.criar();
		encerradaComAudio();
		encerradaComAudio();

		mvc.perform(get("/api/consultas?status=ENCERRADA").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.itens", hasSize(2)));
		mvc.perform(get("/api/consultas?status=CRIADA").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.total").value(1));
		mvc.perform(get("/api/consultas?pagina=1&tamanho=2").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.itens", hasSize(1))).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.pagina").value(1));
		mvc.perform(get("/api/consultas?tamanho=100000").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.tamanho").value(50));
		mvc.perform(get("/api/consultas?status=INEXISTENTE").with(user(cenario.emailMedico))).andExpect(status().isBadRequest());
	}

	@Test
	void ordenaDaMaisRecenteParaAMaisAntiga() throws Exception {
		Consulta a = cenario.criar();
		Consulta b = cenario.criar();
		jdbc.update("UPDATE agendamento SET data_hora = ? WHERE id = (SELECT agendamento_id FROM consulta WHERE id = ?)", java.sql.Timestamp.from(Instant.now().plus(10, ChronoUnit.DAYS)), UUID.fromString(a.id()));

		mvc.perform(get("/api/consultas").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.itens[0].consultaId").value(a.id())).andExpect(jsonPath("$.itens[1].consultaId").value(b.id()));
	}

	@Test
	void semLoginOuComSessaoPresaAUmaConsultaNaoListaNada() throws Exception {
		Consulta c = cenario.criar();
		var agendamento = jdbc.queryForObject("SELECT agendamento_id FROM consulta WHERE id = ?", UUID.class, UUID.fromString(c.id()));
		String sessao = sessoes.emitir(cenario.emailMedico, UUID.fromString(c.id()), agendamento, Modo.LEITURA, Instant.now().plusSeconds(600));

		mvc.perform(get("/api/consultas")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas").header("Authorization", "Bearer " + sessao)).andExpect(status().isForbidden());
	}

	@Test
	void aListagemTrazORoomNameEOStatusDoAgendamentoParaRevisarOLinkDoPaciente() throws Exception {
		Consulta c = cenario.criar();

		mvc.perform(get("/api/consultas").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.itens[0].roomName").value(c.sala()))
				.andExpect(jsonPath("$.itens[0].agendamentoStatus").value("AGENDADO"));
	}

	@Test
	void medicoCancelaConsultaAgendadaQueAindaNaoComecou() throws Exception {
		Consulta c = cenario.criar();

		mvc.perform(post("/api/consultas/" + c.id() + "/cancelar").with(user(cenario.emailMedico))).andExpect(status().isNoContent());
		mvc.perform(post("/api/consultas/" + c.id() + "/cancelar").with(user(cenario.emailMedico))).andExpect(status().isNoContent()); // idempotente

		mvc.perform(get("/api/consultas").with(user(cenario.emailMedico)))
				.andExpect(jsonPath("$.itens[0].agendamentoStatus").value("CANCELADO"));
	}

	@Test
	void naoCancelaConsultaJaEmAndamento() throws Exception {
		Consulta c = cenario.criar();
		cenario.consentir(c, true);
		cenario.iniciar(c);

		mvc.perform(post("/api/consultas/" + c.id() + "/cancelar").with(user(cenario.emailMedico))).andExpect(status().isConflict());
	}

	@Test
	void medicoNaoCancelaConsultaDeOutro() throws Exception {
		Consulta c = cenario.criar();
		var outro = new CenarioDeConsulta(mvc, medicos);

		mvc.perform(post("/api/consultas/" + c.id() + "/cancelar").with(user(outro.emailMedico))).andExpect(status().isNotFound());
		mvc.perform(post("/api/consultas/" + c.id() + "/cancelar")).andExpect(status().isUnauthorized());
	}
}
