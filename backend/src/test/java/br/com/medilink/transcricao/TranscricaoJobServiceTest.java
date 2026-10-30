package br.com.medilink.transcricao;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T12 / RF-06: encerrada a consulta, um job com referência ao áudio é enfileirado, e só quando o áudio já fechou. */
@SpringBootTest
@AutoConfigureMockMvc
class TranscricaoJobServiceTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService service;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;
	@Autowired JsonMapper json;
	@MockitoBean PublicadorDeJobs publicador;

	private CenarioDeConsulta cenario;
	private Consulta c;
	private UUID consultaId;

	@BeforeEach
	void montar() throws Exception {
		reset(publicador);
		cenario = new CenarioDeConsulta(mvc, medicos);
		c = cenario.criar();
		consultaId = UUID.fromString(c.id());
	}

	/** Consulta que foi até o fim, com o paciente consentindo. */
	private void consultaEncerrada() throws Exception {
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
	}

	private AudioTransitorio arquivo(String endpoint, boolean completo) {
		AudioTransitorio a = new AudioTransitorio(consultaId, endpoint + "-a0", endpoint,
				c.id() + "/" + endpoint + "-a0-1.opus", Instant.now().minusSeconds(60));
		if (completo) {
			a.completar(Instant.now(), 50L * 960);
		}
		return audios.save(a);
	}

	private void registrar(String endpoint, boolean medico) throws Exception {
		var req = post("/api/consultas/" + c.id() + "/participante").contentType(MediaType.APPLICATION_JSON);
		mvc.perform(medico ? req.with(user(cenario.emailMedico)).content("{\"endpointId\":\"" + endpoint + "\"}")
				: req.content("{\"endpointId\":\"" + endpoint + "\",\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isOk());
	}

	private JobStatus statusDoJob() {
		return jobs.findByConsultaId(consultaId).orElseThrow().getStatus();
	}

	@Test
	void encerrarCriaUmJobPendenteEUmSo() throws Exception {
		consultaEncerrada();
		mvc.perform(post("/api/consultas/" + c.id() + "/encerrar").with(user(cenario.emailMedico))).andExpect(status().isOk());

		assertThat(jobs.findAll().stream().filter(j -> j.getConsultaId().equals(consultaId))).hasSize(1);
		assertThat(statusDoJob()).isEqualTo(JobStatus.PENDENTE);
	}

	@Test
	void semAudioNaoHaOQueTranscrever() throws Exception {
		consultaEncerrada();

		service.publicarPendentes();

		assertThat(statusDoJob()).isEqualTo(JobStatus.SEM_AUDIO);
		verify(publicador, never()).publicar(any(), any());
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("TRANSCRICAO_SEM_AUDIO");
	}

	@Test
	void publicaUmaVezComReferenciasEPapeisSemConteudo() throws Exception {
		cenario.consentir(c, true);
		cenario.iniciar(c);
		registrar("med00001", true);   // os participantes se registram durante a consulta
		registrar("pac00001", false);
		cenario.encerrar(c);
		arquivo("med00001", true);
		arquivo("pac00001", true);
		arquivo("desc0001", true); // fonte sem participante registrado

		int enviados = service.publicarPendentes();
		service.publicarPendentes(); // a segunda varredura não republica

		assertThat(enviados).isEqualTo(1);
		assertThat(statusDoJob()).isEqualTo(JobStatus.ENFILEIRADO);
		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(publicador, times(1)).publicar(eq(c.id()), msg.capture());
		JsonNode m = json.readTree(msg.getValue());
		assertThat(m.path("versao").asInt()).isEqualTo(1);
		assertThat(m.path("consultaId").asString()).isEqualTo(c.id());
		assertThat(m.path("jobId").asString()).isEqualTo(jobs.findByConsultaId(consultaId).orElseThrow().getId().toString());
		assertThat(m.path("idioma").asString()).isEqualTo("pt");
		assertThat(m.path("fontes")).hasSize(3);
		var papeis = new java.util.HashMap<String, String>();
		m.path("fontes").forEach(f -> papeis.put(f.path("endpointId").asString(), f.path("papel").asString()));
		assertThat(papeis).containsEntry("med00001", "MEDICO").containsEntry("pac00001", "PACIENTE").containsEntry("desc0001", "DESCONHECIDO");
		m.path("fontes").forEach(f -> {
			assertThat(f.path("arquivo").asString()).startsWith(c.id() + "/").endsWith(".opus");
			assertThat(f.path("iniciadoEm").asString()).isNotBlank();
		});
		// Só referências: nada de CPF, nome ou texto na mensagem.
		assertThat(msg.getValue()).doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("Maria");
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("TRANSCRICAO_ENFILEIRADA");
	}

	@Test
	void esperaOArquivoQueAindaEstaGravando() throws Exception {
		consultaEncerrada();
		arquivo("med00001", false); // o sink ainda não fechou

		assertThat(service.publicarPendentes()).isZero();
		assertThat(statusDoJob()).isEqualTo(JobStatus.PENDENTE);
		verify(publicador, never()).publicar(any(), any());

		audios.findByConsultaIdOrderByIniciadoEm(consultaId).forEach(a -> {
			a.completar(Instant.now(), 960);
			audios.save(a);
		});
		assertThat(service.publicarPendentes()).isEqualTo(1);
	}

	@Test
	void arquivoPresoEmGravandoNaoBloqueiaParaSempre() throws Exception {
		consultaEncerrada();
		arquivo("med00001", false);
		jdbc.update("UPDATE consulta SET fim_em = ? WHERE id = ?", Instant.now().minus(30, ChronoUnit.MINUTES).atOffset(java.time.ZoneOffset.UTC), consultaId);

		assertThat(service.publicarPendentes()).isEqualTo(1);
		assertThat(statusDoJob()).isEqualTo(JobStatus.ENFILEIRADO);
	}

	@Test
	void arquivoDescartadoNaoContaComoAudio() throws Exception {
		consultaEncerrada();
		arquivo("med00001", true);
		jdbc.update("UPDATE audio_transitorio SET status = 'DESCARTADO' WHERE consulta_id = ?", consultaId);

		service.publicarPendentes();

		assertThat(statusDoJob()).isEqualTo(JobStatus.SEM_AUDIO);
	}

	@Test
	void brokerForaDoArMantemOJobPendenteERegistraSoOTipoDoErro() throws Exception {
		consultaEncerrada();
		arquivo("med00001", true);
		doThrow(new IOException("Connection to 10.1.2.3 refused: segredo")).when(publicador).publicar(any(), any());

		assertThat(service.publicarPendentes()).isZero();

		var job = jobs.findByConsultaId(consultaId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(JobStatus.PENDENTE);
		assertThat(job.getTentativas()).isEqualTo(1);
		assertThat(job.getUltimoErro()).isEqualTo("IOException").doesNotContain("segredo");

		reset(publicador); // o broker voltou
		assertThat(service.publicarPendentes()).isEqualTo(1);
		assertThat(statusDoJob()).isEqualTo(JobStatus.ENFILEIRADO);
	}

	@Test
	void desisteDepoisDeMuitasTentativasMantendoOAudio() throws Exception {
		consultaEncerrada();
		arquivo("med00001", true);
		doThrow(new IOException("fora do ar")).when(publicador).publicar(any(), any());

		for (int i = 0; i < 20; i++) {
			service.publicarPendentes();
		}

		var job = jobs.findByConsultaId(consultaId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(JobStatus.FALHA_NO_ENVIO);
		assertThat(job.getTentativas()).isEqualTo(20);
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId)).hasSize(1); // o áudio segue lá
		service.publicarPendentes(); // não tenta mais
		verify(publicador, times(20)).publicar(any(), any());
	}
}
