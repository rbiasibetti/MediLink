package br.com.medilink.transcricao;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.sink.SinkProperties;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService.Desfecho;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService.ResultadoInvalidoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13 / RF-07: o resultado do worker vira {@code Transcricao} e só depois o áudio é apagado. Contra o SQL Server
 * local, com arquivos de verdade no diretório do sink.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResultadoDaTranscricaoServiceTest {

	private static final String FALA_MEDICO = "A senhora sente dor de cabeça há quantos dias?";
	private static final String FALA_PACIENTE = "Faz três dias, doutora.";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService jobsService;
	@Autowired ResultadoDaTranscricaoService service;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired TranscricaoRepository transcricoes;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired SinkProperties sink;
	@Autowired JdbcTemplate jdbc;
	@Autowired JsonMapper json;
	@MockitoBean PublicadorDeJobs publicador;

	private CenarioDeConsulta cenario;
	private Consulta c;
	private UUID consultaId;
	private UUID jobId;
	private Path pasta;

	/** Consulta encerrada, com dois arquivos de áudio de verdade no disco e o job já enfileirado. */
	@BeforeEach
	void montar() throws Exception {
		cenario = new CenarioDeConsulta(mvc, medicos);
		c = cenario.criar();
		consultaId = UUID.fromString(c.id());
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		pasta = sink.dir().resolve(c.id());
		Files.createDirectories(pasta);
		arquivo("med00001");
		arquivo("pac00001");
		jobsService.publicarPendentes();
		var job = jobs.findByConsultaId(consultaId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(JobStatus.ENFILEIRADO);
		jobId = job.getId();
	}

	private AudioTransitorio arquivo(String endpoint) throws Exception {
		String relativo = c.id() + "/" + endpoint + "-1.opus";
		Files.writeString(sink.dir().resolve(relativo), "OggS-conteudo-de-teste");
		AudioTransitorio a = new AudioTransitorio(consultaId, endpoint + "-a0", endpoint, relativo, Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		return audios.save(a);
	}

	private String resultado(String status) {
		return """
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"%s","idioma":"pt","duracaoMs":32249,
				 "segmentos":[
				  {"papel":"PACIENTE","inicioMs":9800,"fimMs":11500,"texto":"%s"},
				  {"papel":"MEDICO","inicioMs":3120,"fimMs":7000,"texto":" %s "}],
				 "fontes":[],"erro":"RuntimeError"}
				""".formatted(jobId, consultaId, status, FALA_PACIENTE, FALA_MEDICO);
	}

	private JobStatus statusDoJob() {
		return jobs.findByConsultaId(consultaId).orElseThrow().getStatus();
	}

	private long arquivosNoDisco() throws Exception {
		if (!Files.exists(pasta)) {
			return 0;
		}
		try (var s = Files.list(pasta)) {
			return s.count();
		}
	}

	@Test
	void gravaATranscricaoComOsFalantesNaOrdemEApagaOAudio() throws Exception {
		assertThat(arquivosNoDisco()).isEqualTo(2);

		assertThat(service.processar(resultado("CONCLUIDO"))).isEqualTo(Desfecho.GRAVADA);

		var t = transcricoes.findByConsultaId(consultaId).orElseThrow();
		assertThat(t.getIdioma()).isEqualTo("pt");
		assertThat(t.getDuracaoMs()).isEqualTo(32249);
		assertThat(t.getTexto()).isEqualTo("Médico: " + FALA_MEDICO + "\nPaciente: " + FALA_PACIENTE);
		JsonNode segmentos = json.readTree(t.getSegmentos());
		assertThat(segmentos).hasSize(2);
		assertThat(segmentos.get(0).path("papel").asString()).isEqualTo("MEDICO"); // ordenado pelo tempo, não pela mensagem
		assertThat(segmentos.get(0).path("inicioMs").asLong()).isEqualTo(3120);
		assertThat(segmentos.get(1).path("papel").asString()).isEqualTo("PACIENTE");

		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(arquivosNoDisco()).isZero();
		assertThat(Files.exists(pasta)).isFalse(); // a pasta da consulta também sai
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId)).hasSize(2)
				.allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AudioStatus.DESCARTADO));
		var trilha = auditoria.findByRecursoOrderByMomento("consulta/" + c.id());
		assertThat(trilha).extracting("acao").contains("TRANSCRICAO_GRAVADA", "AUDIO_DESCARTADO");
		// A auditoria registra o fato, nunca o conteúdo.
		assertThat(trilha).allSatisfy(a -> assertThat(a.getRecurso() + a.getAcao() + a.getAtor())
				.doesNotContain("dor de cabeça").doesNotContain("doutora"));
	}

	@Test
	void entregaRepetidaNaoDuplicaNada() throws Exception {
		service.processar(resultado("CONCLUIDO"));

		assertThat(service.processar(resultado("CONCLUIDO"))).isEqualTo(Desfecho.JA_PROCESSADA);

		assertThat(transcricoes.findAll().stream().filter(t -> t.getConsulta().getId().equals(consultaId))).hasSize(1);
		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
	}

	@Test
	void seOAudioNaoSaiATranscricaoFicaGravadaEODescarteERepetido() throws Exception {
		// Um "arquivo" que é uma pasta não vazia: deleteIfExists falha, como falharia num disco com problema.
		Path preso = pasta.resolve("med00001-1.opus");
		Files.delete(preso);
		Files.createDirectory(preso);
		Files.writeString(preso.resolve("peso"), "x");

		assertThat(service.processar(resultado("CONCLUIDO"))).isEqualTo(Desfecho.GRAVADA);

		assertThat(transcricoes.findByConsultaId(consultaId)).isPresent();           // o texto não se perde
		assertThat(statusDoJob()).isEqualTo(JobStatus.TRANSCRITO);                   // e o job não finge ter terminado
		assertThat(Files.exists(pasta.resolve("pac00001-1.opus"))).isFalse();       // o que deu para apagar, foi
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId)).extracting(AudioTransitorio::getStatus)
				.containsExactlyInAnyOrder(AudioStatus.DESCARTADO, AudioStatus.COMPLETO);
		assertThat(service.descartarPendentes()).isZero();                           // ainda preso

		Files.delete(preso.resolve("peso")); // o problema do disco passou
		assertThat(service.descartarPendentes()).isGreaterThanOrEqualTo(1);

		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(Files.exists(pasta)).isFalse();
	}

	@Test
	void falhaDoWorkerMantemOAudioESemGravarNada() throws Exception {
		assertThat(service.processar(resultado("FALHA"))).isEqualTo(Desfecho.FALHA_NO_WORKER);

		assertThat(transcricoes.findByConsultaId(consultaId)).isEmpty();
		assertThat(arquivosNoDisco()).isEqualTo(2);
		var job = jobs.findByConsultaId(consultaId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(JobStatus.FALHA_NO_WORKER);
		assertThat(job.getUltimoErro()).isEqualTo("RuntimeError");
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId))
				.allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AudioStatus.COMPLETO));

		// Reprocessado com sucesso depois (o job volta a ser tratável), o ciclo se completa.
		assertThat(service.processar(resultado("CONCLUIDO"))).isEqualTo(Desfecho.GRAVADA);
		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(arquivosNoDisco()).isZero();
	}

	@Test
	void resultadoSemTextoEUmaTranscricaoVaziaNaoUmErro() throws Exception {
		String vazio = """
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":4000,"segmentos":[]}
				""".formatted(jobId, consultaId);

		assertThat(service.processar(vazio)).isEqualTo(Desfecho.GRAVADA);

		assertThat(transcricoes.findByConsultaId(consultaId).orElseThrow().getTexto()).isEmpty();
		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
	}

	@Test
	void mensagemForaDoContratoEDescartadaSemTocarEmNada() throws Exception {
		String[] ruins = {
				"isto não é json",
				"[]",
				"{\"versao\":2,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\"}".formatted(jobId, consultaId),
				"{\"versao\":1,\"jobId\":\"nao-e-uuid\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\"}".formatted(consultaId),
				"{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"TALVEZ\"}".formatted(jobId, consultaId),
				"{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\",\"idioma\":\"pt\",\"segmentos\":\"x\"}".formatted(jobId, consultaId),
				"{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\",\"idioma\":\"pt\",\"segmentos\":[{\"inicioMs\":5,\"fimMs\":1,\"texto\":\"a\"}]}".formatted(jobId, consultaId),
		};
		for (String ruim : ruins) {
			assertThatThrownBy(() -> service.processar(ruim)).isInstanceOf(ResultadoInvalidoException.class);
		}

		assertThat(transcricoes.findByConsultaId(consultaId)).isEmpty();
		assertThat(statusDoJob()).isEqualTo(JobStatus.ENFILEIRADO);
		assertThat(arquivosNoDisco()).isEqualTo(2);
	}

	@Test
	void resultadoDeJobDesconhecidoOuDeOutraConsultaEIgnorado() throws Exception {
		String desconhecido = resultado("CONCLUIDO").replace(jobId.toString(), UUID.randomUUID().toString());
		String outraConsulta = resultado("CONCLUIDO").replace(consultaId.toString(), UUID.randomUUID().toString());

		assertThat(service.processar(desconhecido)).isEqualTo(Desfecho.IGNORADA);
		assertThat(service.processar(outraConsulta)).isEqualTo(Desfecho.IGNORADA);

		assertThat(transcricoes.findByConsultaId(consultaId)).isEmpty();
		assertThat(arquivosNoDisco()).isEqualTo(2);
	}

	@Test
	void naoApagaArquivoForaDaPastaDaConsulta() throws Exception {
		Path fora = sink.dir().resolve("fora-" + UUID.randomUUID() + ".opus");
		Files.writeString(fora, "não é meu");
		jdbc.update("UPDATE audio_transitorio SET arquivo = ? WHERE consulta_id = ? AND endpoint_id = 'med00001'",
				c.id() + "/../" + fora.getFileName(), consultaId);
		try {
			service.processar(resultado("CONCLUIDO"));

			assertThat(Files.exists(fora)).isTrue();
			assertThat(statusDoJob()).isEqualTo(JobStatus.TRANSCRITO); // não conclui com um arquivo que não pôde tratar
			assertThat(Files.exists(pasta.resolve("pac00001-1.opus"))).isFalse();
		}
		finally {
			Files.deleteIfExists(fora);
		}
	}

	@Test
	void arquivoAindaGravandoEEsperadoAteOPrazo() throws Exception {
		jdbc.update("UPDATE audio_transitorio SET status = 'GRAVANDO' WHERE consulta_id = ? AND endpoint_id = 'med00001'", consultaId);

		service.processar(resultado("CONCLUIDO"));

		assertThat(statusDoJob()).isEqualTo(JobStatus.TRANSCRITO);
		assertThat(Files.exists(pasta.resolve("med00001-1.opus"))).isTrue(); // o sink ainda pode estar escrevendo nele

		jdbc.update("UPDATE consulta SET fim_em = ? WHERE id = ?", Instant.now().minus(30, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), consultaId);
		service.descartarPendentes(); // linha presa em GRAVANDO depois do prazo: apaga assim mesmo

		assertThat(statusDoJob()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(arquivosNoDisco()).isZero();
	}
}
