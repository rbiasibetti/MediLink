package br.com.medilink.transcricao;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.sink.SinkProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Consumidor de verdade contra o Redpanda local (infra/dev/redpanda.sh up): uma mensagem no tópico de resultados
 * vira transcrição gravada e áudio apagado. Sem o broker o teste é PULADO. Usa um grupo próprio para não competir
 * com um backend de desenvolvimento rodando ao mesmo tempo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
		"medilink.transcricao.consumidor-ativo=true",
		"medilink.transcricao.grupo-consumidor=teste-${random.uuid}"})
class ConsumidorDeResultadosIntegracaoTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired TranscricaoJobService jobsService;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired TranscricaoRepository transcricoes;
	@Autowired AudioTransitorioRepository audios;
	@Autowired TranscricaoProperties props;
	@Autowired SinkProperties sink;
	@Autowired KafkaTemplate<String, String> kafka;
	@Value("${spring.kafka.bootstrap-servers}") String broker;
	@MockitoSpyBean ResultadoDaTranscricaoService service;
	@MockitoBean PublicadorDeJobs publicador; // o job não precisa ir de verdade ao tópico de jobs

	@BeforeEach
	void brokerNoAr() {
		String[] hp = broker.split(":");
		boolean ok;
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 1500);
			ok = true;
		}
		catch (Exception e) {
			ok = false;
		}
		assumeTrue(ok, "Redpanda indisponível em " + broker + " (rode infra/dev/redpanda.sh up)");
	}

	private record Preparada(Consulta consulta, UUID consultaId, UUID jobId, Path pasta) {
	}

	/** Consulta encerrada com um arquivo de áudio de verdade no disco e o job enfileirado. */
	private Preparada prepararJobEnfileirado() throws Exception {
		var cenario = new CenarioDeConsulta(mvc, medicos);
		Consulta c = cenario.criar();
		UUID consultaId = UUID.fromString(c.id());
		cenario.consentir(c, true);
		cenario.iniciar(c);
		cenario.encerrar(c);
		Path pasta = sink.dir().resolve(c.id());
		Files.createDirectories(pasta);
		Files.writeString(pasta.resolve("med00001-1.opus"), "OggS-conteudo-de-teste");
		AudioTransitorio a = new AudioTransitorio(consultaId, "med-a0", "med00001", c.id() + "/med00001-1.opus", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		audios.save(a);
		jobsService.publicarPendentes();
		return new Preparada(c, consultaId, jobs.findByConsultaId(consultaId).orElseThrow().getId(), pasta);
	}

	private String resultado(Preparada p) {
		return """
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":5000,
				 "segmentos":[{"papel":"MEDICO","inicioMs":100,"fimMs":2000,"texto":"Frase confidencial da consulta."}]}
				""".formatted(p.jobId(), p.consultaId());
	}

	private void aguardarConclusao(UUID consultaId, long ms) throws InterruptedException {
		long limite = System.currentTimeMillis() + ms;
		while (jobs.findByConsultaId(consultaId).orElseThrow().getStatus() != JobStatus.CONCLUIDO && System.currentTimeMillis() < limite) {
			Thread.sleep(250);
		}
	}

	@Test
	void resultadoNoTopicoViraTranscricaoGravadaEAudioApagado() throws Exception {
		Preparada p = prepararJobEnfileirado();

		// Uma mensagem fora do contrato no meio não pode travar a fila.
		kafka.send(props.topicoResultados(), p.consulta().id(), "isto não é json").get();
		kafka.send(props.topicoResultados(), p.consulta().id(), resultado(p)).get();
		aguardarConclusao(p.consultaId(), 30_000);

		assertThat(jobs.findByConsultaId(p.consultaId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(transcricoes.findByConsultaId(p.consultaId()).orElseThrow().getTexto()).isEqualTo("Médico: Frase confidencial da consulta.");
		assertThat(Files.exists(p.pasta())).isFalse();
	}

	@Test
	void falhaTransitoriaRepeteAMensagemSemPerderOResultadoENemVazarOTextoNoLog(CapturedOutput saida) throws Exception {
		Preparada p = prepararJobEnfileirado();
		// O banco "cai" na primeira tentativa; o consumidor tem de repetir a mesma mensagem, e não pular o offset.
		doThrow(new IllegalStateException("banco indisponível")).doCallRealMethod().when(service).processar(any());

		kafka.send(props.topicoResultados(), p.consulta().id(), resultado(p)).get();
		aguardarConclusao(p.consultaId(), 40_000);

		assertThat(jobs.findByConsultaId(p.consultaId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CONCLUIDO);
		assertThat(Files.exists(p.pasta())).isFalse();
		assertThat(saida.getAll()).contains("falha ao processar o resultado", "IllegalStateException") // a falha foi logada...
				.doesNotContain("Frase confidencial", "banco indisponível"); // ...sem o conteúdo da consulta nem o texto do erro
	}
}
