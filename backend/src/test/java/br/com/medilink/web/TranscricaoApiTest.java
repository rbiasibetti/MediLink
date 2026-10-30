package br.com.medilink.web;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T13: {@code GET /api/consultas/{id}/transcricao}. Só o médico dono lê; enquanto o worker processa, 202. */
@SpringBootTest
@AutoConfigureMockMvc
class TranscricaoApiTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService jobsService;
	@Autowired ResultadoDaTranscricaoService resultados;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean PublicadorDeJobs publicador;

	private CenarioDeConsulta cenario;
	private Consulta c;
	private UUID consultaId;

	@BeforeEach
	void montar() throws Exception {
		cenario = new CenarioDeConsulta(mvc, medicos);
		c = cenario.criar();
		consultaId = UUID.fromString(c.id());
	}

	private void encerrarComAudioEEnfileirar() throws Exception {
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		AudioTransitorio a = new AudioTransitorio(consultaId, "med-a0", "med00001", c.id() + "/med00001-1.opus", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		audios.save(a);
		jobsService.publicarPendentes();
	}

	private void entregarResultado() {
		UUID jobId = jobs.findByConsultaId(consultaId).orElseThrow().getId();
		resultados.processar("""
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":9000,
				 "segmentos":[{"papel":"MEDICO","inicioMs":100,"fimMs":2000,"texto":"Bom dia."},
				              {"papel":"PACIENTE","inicioMs":2500,"fimMs":4000,"texto":"Bom dia, doutora."}]}
				""".formatted(jobId, consultaId));
	}

	@Test
	void medicoLeATranscricaoComOsFalantesETemposEALeituraFicaAuditada() throws Exception {
		encerrarComAudioEEnfileirar();
		entregarResultado();

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.consultaId").value(c.id()))
				.andExpect(jsonPath("$.idioma").value("pt"))
				.andExpect(jsonPath("$.duracaoMs").value(9000))
				.andExpect(jsonPath("$.segmentos", hasSize(2)))
				.andExpect(jsonPath("$.segmentos[0].papel").value("MEDICO"))
				.andExpect(jsonPath("$.segmentos[0].inicioMs").value(100))
				.andExpect(jsonPath("$.segmentos[0].texto").value("Bom dia."))
				.andExpect(jsonPath("$.segmentos[1].papel").value("PACIENTE"))
				.andExpect(jsonPath("$.texto").value("Médico: Bom dia.\nPaciente: Bom dia, doutora."));

		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id()))
				.filteredOn(a -> a.getAcao().equals("TRANSCRICAO_LIDA")).hasSize(1)
				.allSatisfy(a -> assertThat(a.getAtor()).isEqualTo(cenario.emailMedico));
	}

	@Test
	void enquantoOWorkerProcessaResponde202SemTexto() throws Exception {
		encerrarComAudioEEnfileirar();

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("EM_PROCESSAMENTO"))
				.andExpect(jsonPath("$.segmentos").doesNotExist());
	}

	@Test
	void consultaAindaAbertaEConflito() throws Exception {
		cenario.consentir(c, true);
		cenario.iniciar(c);

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
				.andExpect(status().isConflict());
	}

	@Test
	void consultaSemAudioNaoTemTranscricao() throws Exception {
		cenario.consentir(c, false);
		cenario.iniciar(c);
		cenario.encerrar(c);
		jobsService.publicarPendentes();

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value(not(containsString("null"))));
	}

	@Test
	void falhaDoWorkerApareceComoConflitoEOAudioFica() throws Exception {
		encerrarComAudioEEnfileirar();
		UUID jobId = jobs.findByConsultaId(consultaId).orElseThrow().getId();
		resultados.processar("{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"FALHA\",\"erro\":\"RuntimeError\"}"
				.formatted(jobId, consultaId));

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
				.andExpect(status().isConflict());
	}

	@Test
	void outroMedicoNaoEnxergaAConsulta() throws Exception {
		encerrarComAudioEEnfileirar();
		entregarResultado();
		var outro = new CenarioDeConsulta(mvc, medicos);

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(outro.emailMedico)))
				.andExpect(status().isNotFound());
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id()))
				.filteredOn(a -> a.getAcao().equals("TRANSCRICAO_LIDA")).isEmpty();
		// A tentativa negada fica registrada, com quem tentou, mesmo a resposta sendo 404.
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id()))
				.filteredOn(a -> a.getAcao().equals("TRANSCRICAO_ACESSO_NEGADO")).hasSize(1)
				.allSatisfy(a -> assertThat(a.getAtor()).isEqualTo(outro.emailMedico));
	}

	@Test
	void transcricaoOuAudioExpiradosDao410() throws Exception {
		encerrarComAudioEEnfileirar();
		jdbc.update("UPDATE transcricao_job SET status = 'EXPIRADO' WHERE consulta_id = ?", consultaId);
		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico))).andExpect(status().isGone());

		jdbc.update("UPDATE transcricao_job SET status = 'CONCLUIDO' WHERE consulta_id = ?", consultaId);
		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico))).andExpect(status().isGone());
	}

	@Test
	void semLoginOuComoPacienteNaoLe() throws Exception {
		encerrarComAudioEEnfileirar();
		entregarResultado();

		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").header("Authorization", "Bearer " + c.entrada()))
				.andExpect(status().isUnauthorized());
	}
}
