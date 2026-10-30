package br.com.medilink.sink;

import br.com.medilink.crypto.Cifrador;
import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T11: o audio sink contra um servidor real, com um cliente que imita o JVB e o SQL Server local. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AudioSinkIntegracaoTest {

	private static final String CPF = "529.982.247-25";
	private static final String TOKEN = "token-do-sink-de-teste-com-mais-de-32-caracteres";

	@LocalServerPort int porta;
	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AudioTransitorioRepository audios;
	@Autowired AuditLogRepository auditoria;
	@Autowired Cifrador cifrador;
	@Value("${medilink.sink.dir}") Path pasta;

	private String emailMedico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	// ---- cenário de consulta ----

	private record Consulta(String sala, String id, String entrada) {
	}

	private Consulta criarConsulta() throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s"}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS)))).andReturn().getResponse().getContentAsString();
		String sala = campo(json, "roomName");
		String entrada = campo(mvc.perform(post("/api/salas/" + sala + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + CPF + "\"}")).andReturn().getResponse().getContentAsString(), "tokenEntrada");
		return new Consulta(sala, campo(json, "consultaId"), entrada);
	}

	private static String campo(String json, String nome) {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", nome, json).isTrue();
		return m.group(1);
	}

	private void iniciar(Consulta c) throws Exception {
		mvc.perform(post("/api/salas/" + c.sala() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + c.id() + "/aceite").with(user(emailMedico))).andExpect(status().isOk());
	}

	private void consentir(Consulta c, boolean aceito) throws Exception {
		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"%s\",\"aceito\":%s,\"versaoTermo\":\"v1\"}".formatted(c.entrada(), aceito)))
				.andExpect(status().isOk());
	}

	/** Consulta em andamento e com consentimento: a captura está autorizada. */
	private Consulta consultaAutorizada() throws Exception {
		Consulta c = criarConsulta();
		consentir(c, true);
		iniciar(c);
		return c;
	}

	// ---- cliente que imita o JVB ----

	private static final class Jvb implements WebSocket.Listener {
		final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
		volatile int codigoDeFechamento = -1;

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			recebidas.add(data.toString());
			ws.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			codigoDeFechamento = code;
			return null;
		}
	}

	private CompletableFuture<WebSocket> conectar(Jvb jvb, String token, String sala) {
		return HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer " + token)
				.buildAsync(URI.create("ws://localhost:" + porta + "/api/audio-sink/reuniao-de-teste" + (sala == null ? "" : "?sala=" + sala)), jvb);
	}

	private WebSocket conectarOk(Jvb jvb, String sala) {
		return conectar(jvb, TOKEN, sala).join();
	}

	private static String start(String tag) {
		return "{\"event\":\"start\",\"sequenceNumber\":\"1\",\"start\":{\"tag\":\"" + tag + "\",\"mediaFormat\":{\"encoding\":\"opus\","
				+ "\"sampleRate\":48000,\"channels\":2},\"customParameters\":{\"endpointId\":\"abc123\"}}}";
	}

	private static String media(String tag, long chunk, long timestamp, byte[] pacote) {
		return "{\"event\":\"media\",\"sequenceNumber\":\"" + chunk + "\",\"media\":{\"tag\":\"" + tag + "\",\"chunk\":\"" + chunk
				+ "\",\"timestamp\":\"" + timestamp + "\",\"payload\":\"" + Base64.getEncoder().encodeToString(pacote) + "\"}}";
	}

	private static byte[] pacote(int i) {
		return new byte[] { (byte) 0xFC, (byte) (i & 0xFF), (byte) (i >> 8), 7, 7 }; // CELT FB 20 ms estéreo = 960 amostras
	}

	private static void enviar(WebSocket ws, String texto) {
		ws.sendText(texto, true).join();
	}

	/** O arquivo no disco é sempre cifrado (T14); aqui o teste o decifra para conferir o Ogg. */
	private byte[] claro(Path arquivo) throws Exception {
		byte[] bruto = Files.readAllBytes(arquivo);
		assertThat(new String(bruto, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("MLA1"); // nada de áudio claro no disco
		assertThat(new String(bruto, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("OggS").doesNotContain("OpusHead");
		return cifrador.decifrarAudio(new java.io.ByteArrayInputStream(bruto));
	}

	private List<Path> arquivos(Consulta c) throws Exception {
		Path dir = pasta.resolve(c.id());
		if (!Files.exists(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.sorted().toList();
		}
	}

	// ---- testes ----

	@Test
	void recusaOHandshakeSemOTokenCertoOuSemASala() {
		assertThatThrownBy(() -> conectar(new Jvb(), "errado", "consulta-x").join())
				.isInstanceOf(CompletionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class)
				.satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(401));
		assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
				.buildAsync(URI.create("ws://localhost:" + porta + "/api/audio-sink/reuniao?sala=consulta-x"), new Jvb()).join())
				.hasCauseInstanceOf(WebSocketHandshakeException.class);
		assertThatThrownBy(() -> conectar(new Jvb(), TOKEN, null).join())
				.satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(400));
		assertThatThrownBy(() -> conectar(new Jvb(), TOKEN, "../etc").join())
				.hasCauseInstanceOf(WebSocketHandshakeException.class);
	}

	@Test
	void salaDesconhecidaFechaAConexao() {
		Jvb jvb = new Jvb();
		conectarOk(jvb, "consulta-nao-existe");

		await().atMost(5, TimeUnit.SECONDS).until(() -> jvb.codigoDeFechamento == 1008);
	}

	@Test
	void respondePingComPongDoMesmoId() throws Exception {
		Consulta c = criarConsulta();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());

		enviar(ws, "{\"event\":\"ping\",\"id\":42}");

		assertThat(jvb.recebidas.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"event\":\"pong\",\"id\":42}");
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
	}

	@Test
	void comConsentimentoGravaUmArquivoOggOpusPorFonteERegistraNoBanco() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());

		enviar(ws, "{\"event\":\"info\",\"application\":\"jitsi-videobridge\"}");
		enviar(ws, start("medico-a0"));
		enviar(ws, start("paciente-a0"));
		for (int i = 0; i < 50; i++) {
			enviar(ws, media("medico-a0", 100 + i, 1000L + i * 960L, pacote(i)));
			enviar(ws, media("paciente-a0", 200 + i, 5000L + i * 960L, pacote(1000 + i)));
		}
		enviar(ws, "{\"event\":\"stop\",\"sequenceNumber\":\"9\",\"stop\":{\"tag\":\"medico-a0\"}}");
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();

		await().atMost(10, TimeUnit.SECONDS).until(() -> audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id())).stream()
				.allMatch(a -> a.getStatus() == AudioStatus.COMPLETO) && arquivos(c).size() == 2);
		var registros = audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id()));
		assertThat(registros).hasSize(2).extracting("fonte").containsExactlyInAnyOrder("medico-a0", "paciente-a0");
		assertThat(registros).allSatisfy(r -> {
			assertThat(r.getAmostras()).isEqualTo(50L * 960);
			assertThat(r.getEndpointId()).isEqualTo("abc123");
			assertThat(r.getFinalizadoEm()).isNotNull();
			Path arq = pasta.resolve(r.getArquivo());
			assertThat(arq).exists().startsWith(pasta);
			assertThat(OggLeitor.pacotesDeAudio(claro(arq))).hasSize(50);
		});
		// O conteúdo de cada fonte é o dela (não se misturam).
		var medico = registros.stream().filter(r -> r.getFonte().equals("medico-a0")).findFirst().orElseThrow();
		assertThat(OggLeitor.pacotesDeAudio(claro(pasta.resolve(medico.getArquivo()))).get(3)).isEqualTo(pacote(3));
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("CAPTURA_ABERTA", "CAPTURA_FECHADA");
	}

	@Test
	void arquivosSaoPrivadosDoDono() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());
		enviar(ws, start("a0"));
		enviar(ws, media("a0", 1, 1000, pacote(1)));
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
		await().atMost(10, TimeUnit.SECONDS).until(() -> arquivos(c).size() == 1);

		assertThat(Files.getPosixFilePermissions(arquivos(c).get(0))).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(Files.getPosixFilePermissions(pasta.resolve(c.id()))).isEqualTo(PosixFilePermissions.fromString("rwx------"));
	}

	@Test
	void lacunasNaLinhaDoTempoViramSilencioEPacotesRepetidosOuForaDeOrdemSaoIgnorados() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());
		enviar(ws, start("a0"));

		for (int i = 0; i < 5; i++) {
			enviar(ws, media("a0", i, 10_000L + i * 960L, pacote(i)));
		}
		enviar(ws, media("a0", 99, 10_000L + 4 * 960L, pacote(99)));          // repetido: ignorado
		enviar(ws, media("a0", 98, 10_000L + 2 * 960L, pacote(98)));          // fora de ordem: ignorado
		// pulo de 10 quadros: faltam 10 (os da posição 5 a 14); o próximo real vem em 5 + 10 = 15
		enviar(ws, media("a0", 5, 10_000L + 15 * 960L, pacote(15)));
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();

		await().atMost(10, TimeUnit.SECONDS).until(() -> arquivos(c).size() == 1
				&& audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id())).get(0).getStatus() == AudioStatus.COMPLETO);
		List<byte[]> pacotes = OggLeitor.pacotesDeAudio(claro(arquivos(c).get(0)));
		assertThat(pacotes).hasSize(5 + 10 + 1);
		assertThat(pacotes.get(4)).isEqualTo(pacote(4));
		assertThat(pacotes.get(5)).containsExactly((byte) 0xFC, (byte) 0xFF, (byte) 0xFE); // silêncio
		assertThat(pacotes.get(14)).containsExactly((byte) 0xFC, (byte) 0xFF, (byte) 0xFE);
		assertThat(pacotes.get(15)).isEqualTo(pacote(15));
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id())).get(0).getAmostras()).isEqualTo(16L * 960);
	}

	@Test
	void semConsentimentoNadaEGravado() throws Exception {
		Consulta c = criarConsulta();
		iniciar(c); // em andamento, mas o paciente NÃO consentiu
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());

		enviar(ws, start("a0"));
		for (int i = 0; i < 20; i++) {
			enviar(ws, media("a0", i, 1000L + i * 960L, pacote(i)));
		}
		enviar(ws, "{\"event\":\"ping\",\"id\":1}");
		assertThat(jvb.recebidas.poll(5, TimeUnit.SECONDS)).contains("pong"); // o sink processou tudo
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();

		assertThat(arquivos(c)).isEmpty();
		assertThat(audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id()))).isEmpty();
	}

	@Test
	void consentimentoSemConsultaEmAndamentoTambemNaoGrava() throws Exception {
		Consulta c = criarConsulta();
		consentir(c, true); // consentiu, mas o médico ainda não aceitou: a consulta não começou
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());

		enviar(ws, start("a0"));
		enviar(ws, media("a0", 1, 1000, pacote(1)));
		enviar(ws, "{\"event\":\"ping\",\"id\":1}");
		assertThat(jvb.recebidas.poll(5, TimeUnit.SECONDS)).contains("pong");
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();

		assertThat(arquivos(c)).isEmpty();
	}

	@Test
	void retirarOConsentimentoFechaOArquivoEPara() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());
		enviar(ws, start("a0"));
		for (int i = 0; i < 10; i++) {
			enviar(ws, media("a0", i, 1000L + i * 960L, pacote(i)));
		}

		consentir(c, false); // o paciente retira o consentimento no meio da consulta
		Thread.sleep(1300);  // a decisão do portão vale por 1 s
		for (int i = 10; i < 30; i++) {
			enviar(ws, media("a0", i, 1000L + i * 960L, pacote(i)));
		}
		enviar(ws, "{\"event\":\"ping\",\"id\":1}");
		assertThat(jvb.recebidas.poll(5, TimeUnit.SECONDS)).contains("pong");

		var registros = audios.findByConsultaIdOrderByIniciadoEm(UUID.fromString(c.id()));
		assertThat(registros).hasSize(1);
		assertThat(registros.get(0).getStatus()).isEqualTo(AudioStatus.COMPLETO);
		assertThat(registros.get(0).getAmostras()).isEqualTo(10L * 960);
		assertThat(OggLeitor.pacotesDeAudio(claro(pasta.resolve(registros.get(0).getArquivo())))).hasSize(10);
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
	}

	@Test
	void mensagensInvalidasNaoDerrubamAConexao() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());

		enviar(ws, "isto não é json");
		enviar(ws, start("a0"));
		enviar(ws, "{\"event\":\"media\",\"media\":{\"tag\":\"a0\",\"timestamp\":\"abc\",\"payload\":\"!!!\"}}");
		enviar(ws, media("a0", 1, 1000, new byte[0]));
		enviar(ws, media("a0", 2, 2000, new byte[] { (byte) 0xFB, 0 })); // código 3 sem quadros
		enviar(ws, "{\"event\":\"ping\",\"id\":7}");

		assertThat(jvb.recebidas.poll(5, TimeUnit.SECONDS)).isEqualTo("{\"event\":\"pong\",\"id\":7}");
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
	}

	@Test
	void tagComCaracteresPerigososNaoSaiDaPasta() throws Exception {
		Consulta c = consultaAutorizada();
		Jvb jvb = new Jvb();
		WebSocket ws = conectarOk(jvb, c.sala());
		String tag = "../../etc/passwd";
		enviar(ws, start(tag));
		enviar(ws, media(tag, 1, 1000, pacote(1)));
		ws.sendClose(WebSocket.NORMAL_CLOSURE, "fim").join();
		await().atMost(10, TimeUnit.SECONDS).until(() -> arquivos(c).size() == 1);

		assertThat(arquivos(c).get(0).getFileName().toString()).startsWith(".._.._etc_passwd-").endsWith(".opus.enc");
		assertThat(arquivos(c).get(0).normalize().startsWith(pasta.resolve(c.id()))).isTrue();
		assertThat(AudioSinkHandler.nomeSeguro("")).isEqualTo("fonte");
	}
}
