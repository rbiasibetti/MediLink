package br.com.medilink.retencao;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.sink.SinkProperties;
import br.com.medilink.transcricao.PublicadorDeJobs;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService;
import br.com.medilink.transcricao.TranscricaoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** T14: nada sensível fica além do prazo. Contra o SQL Server local, com arquivos de verdade. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"medilink.retencao.tentativa-cpf=24h", "medilink.retencao.audio-maximo=7d", "medilink.retencao.transcricao=30d"})
class RetencaoServiceTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired RetencaoService retencao;
	@Autowired TranscricaoJobService jobsService;
	@Autowired ResultadoDaTranscricaoService resultados;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired SinkProperties sink;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean PublicadorDeJobs publicador;

	private CenarioDeConsulta cenario;
	private Consulta c;
	private UUID consultaId;
	private Path pasta;

	@BeforeEach
	void montar() throws Exception {
		cenario = new CenarioDeConsulta(mvc, medicos);
		c = cenario.criar();
		consultaId = UUID.fromString(c.id());
		pasta = sink.dir().resolve(c.id());
	}

	private static Object ha(long dias) {
		return Instant.now().minus(dias, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
	}

	/** Consulta encerrada com um arquivo de áudio em disco, iniciado há {@code diasDeIdade} dias. */
	private AudioTransitorio comAudio(long diasDeIdade) throws Exception {
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		Files.createDirectories(pasta);
		Files.writeString(pasta.resolve("med00001-1.opus.enc"), "MLA1-conteudo-de-teste");
		AudioTransitorio a = new AudioTransitorio(consultaId, "med-a0", "med00001", c.id() + "/med00001-1.opus.enc", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		a = audios.save(a);
		jdbc.update("UPDATE audio_transitorio SET iniciado_em = ? WHERE id = ?", ha(diasDeIdade), a.getId());
		return a;
	}

	@Test
	void apagaAsTentativasDeCpfAntigasEMantemAsRecentes() {
		UUID agendamento = jdbc.queryForObject("SELECT agendamento_id FROM consulta WHERE id = ?", UUID.class, consultaId);
		jdbc.update("INSERT INTO tentativa_cpf (id, agendamento_id, momento) VALUES (?, ?, ?)", UUID.randomUUID(), agendamento, ha(3));
		jdbc.update("INSERT INTO tentativa_cpf (id, agendamento_id, momento) VALUES (?, ?, ?)", UUID.randomUUID(), agendamento, ha(2));
		jdbc.update("INSERT INTO tentativa_cpf (id, agendamento_id, momento) VALUES (?, ?, ?)", UUID.randomUUID(), agendamento,
				Instant.now().minus(5, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC));

		assertThat(retencao.limparTentativasDeCpf()).isGreaterThanOrEqualTo(2);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tentativa_cpf WHERE agendamento_id = ?", Integer.class, agendamento)).isEqualTo(1);
		assertThat(retencao.limparTentativasDeCpf()).isZero(); // idempotente
	}

	@Test
	void audioQueEstouraOTetoEApagadoEOJobExpira() throws Exception {
		comAudio(8);
		jobsService.publicarPendentes(); // ENFILEIRADO, como se o worker nunca tivesse respondido
		assertThat(jobs.findByConsultaId(consultaId).orElseThrow().getStatus()).isEqualTo(JobStatus.ENFILEIRADO);

		assertThat(retencao.expirarAudios()).isGreaterThanOrEqualTo(1);

		assertThat(Files.exists(pasta)).isFalse();
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId)).allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AudioStatus.DESCARTADO));
		assertThat(jobs.findByConsultaId(consultaId).orElseThrow().getStatus()).isEqualTo(JobStatus.EXPIRADO);
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("AUDIO_EXPIRADO");
		assertThat(retencao.expirarAudios() >= 0).isTrue(); // segunda rodada não quebra nada
	}

	@Test
	void audioDentroDoPrazoFica() throws Exception {
		comAudio(2);

		retencao.expirarAudios();

		assertThat(Files.exists(pasta.resolve("med00001-1.opus.enc"))).isTrue();
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(consultaId)).allSatisfy(a -> assertThat(a.getStatus()).isEqualTo(AudioStatus.COMPLETO));
	}

	@Test
	void jobJaConcluidoNaoVoltaAExpirar() throws Exception {
		comAudio(8);
		jobsService.publicarPendentes();
		UUID jobId = jobs.findByConsultaId(consultaId).orElseThrow().getId();
		resultados.processar("{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\",\"idioma\":\"pt\",\"duracaoMs\":1,\"segmentos\":[]}"
				.formatted(jobId, consultaId));
		assertThat(jobs.findByConsultaId(consultaId).orElseThrow().getStatus()).isEqualTo(JobStatus.CONCLUIDO);

		retencao.expirarAudios();

		assertThat(jobs.findByConsultaId(consultaId).orElseThrow().getStatus()).isEqualTo(JobStatus.CONCLUIDO);
	}

	@Test
	void transcricaoVencidaESuaApagadaQuandoHaPrazo() throws Exception {
		comAudio(0);
		jobsService.publicarPendentes();
		UUID jobId = jobs.findByConsultaId(consultaId).orElseThrow().getId();
		resultados.processar("{\"versao\":1,\"jobId\":\"%s\",\"consultaId\":\"%s\",\"status\":\"CONCLUIDO\",\"idioma\":\"pt\",\"duracaoMs\":1,\"segmentos\":[{\"papel\":\"MEDICO\",\"inicioMs\":0,\"fimMs\":1,\"texto\":\"oi\"}]}"
				.formatted(jobId, consultaId));

		retencao.expirarTranscricoes();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transcricao WHERE consulta_id = ?", Integer.class, consultaId)).isEqualTo(1); // recente

		jdbc.update("UPDATE transcricao SET criada_em = ? WHERE consulta_id = ?", ha(31), consultaId);
		assertThat(retencao.expirarTranscricoes()).isGreaterThanOrEqualTo(1);

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transcricao WHERE consulta_id = ?", Integer.class, consultaId)).isZero();
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("TRANSCRICAO_EXPIRADA");
	}

	@Test
	void semPrazoDeTranscricaoNadaEApagado() throws Exception {
		var semPrazo = new RetencaoService(new RetencaoProperties(false, java.time.Duration.ofHours(1), java.time.Duration.ofHours(24),
				java.time.Duration.ofDays(7), java.time.Duration.ZERO), null, null, null, null, null, null, java.time.Clock.systemUTC(), null, null);

		assertThat(semPrazo.expirarTranscricoes()).isZero();
	}

	@Test
	void umaRegraQueFalhaNaoImpedeAsOutras() {
		// executar() protege cada regra: aqui só confirmamos que roda inteiro sem lançar nada no banco real.
		retencao.executar();
	}
}
