package br.com.medilink.sink;

import br.com.medilink.domain.AudioTransitorio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Audio sink (T11): recebe do JVB, por WebSocket, o áudio Opus de cada participante da reunião (protocolo
 * "mediajson") e o grava em arquivos Ogg Opus transitórios, um por fonte.
 *
 * <p>Regras:
 * <ul>
 * <li>O portão do consentimento fica aqui: sem consulta em andamento e consentimento vigente do paciente, os
 * quadros são descartados e nada é gravado, não importa quem pediu a exportação (o cliente do médico só liga e
 * desliga). Retirar o consentimento durante a consulta fecha os arquivos na hora (no máximo 1 s de atraso).</li>
 * <li>Lacunas na linha do tempo (silêncio/DTX, perda de pacotes) viram quadros de silêncio, para os arquivos
 * das fontes continuarem alinhados no tempo (T12 mistura/ordena as falas).</li>
 * <li>Nada do conteúdo vai para log; só contadores e identificadores.</li>
 * </ul>
 */
@Component
class AudioSinkHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(AudioSinkHandler.class);
	/** Por quanto tempo vale a última consulta ao portão. */
	private static final long VALIDADE_DO_PORTAO_NANOS = 1_000_000_000L;
	/** Teto do preenchimento de uma lacuna: 10 minutos de quadros de 20 ms. */
	private static final long MAX_QUADROS_DE_SILENCIO = 30_000;

	private static final class Conexao {
		final UUID consultaId;
		final Map<String, FonteAudio> fontes = new HashMap<>();
		boolean autorizada;
		long consultadoEm = System.nanoTime() - 2 * VALIDADE_DO_PORTAO_NANOS;

		Conexao(UUID consultaId) {
			this.consultaId = consultaId;
		}
	}

	private final CapturaService captura;
	private final SinkProperties props;
	private final JsonMapper json;
	private final Map<String, Conexao> conexoes = new ConcurrentHashMap<>();

	AudioSinkHandler(CapturaService captura, SinkProperties props, JsonMapper json) {
		this.captura = captura;
		this.props = props;
		this.json = json;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		String sala = (String) session.getAttributes().get(AudioSinkHandshake.SALA);
		Optional<UUID> consulta = captura.consultaDaSala(sala);
		if (consulta.isEmpty()) {
			log.warn("Audio sink: sala desconhecida; conexão recusada (meeting {})", session.getAttributes().get(AudioSinkHandshake.MEETING_ID));
			session.close(CloseStatus.POLICY_VIOLATION.withReason("sala desconhecida"));
			return;
		}
		conexoes.put(session.getId(), new Conexao(consulta.get()));
		log.info("Audio sink: conexão aberta (consulta {}, meeting {})", consulta.get(), session.getAttributes().get(AudioSinkHandshake.MEETING_ID));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage mensagem) throws Exception {
		Conexao c = conexoes.get(session.getId());
		if (c == null) {
			return;
		}
		JsonNode ev;
		try {
			ev = json.readTree(mensagem.getPayload());
		}
		catch (RuntimeException e) {
			return; // mensagem que não é JSON: ignora
		}
		switch (ev.path("event").asString("")) {
			case "ping" -> session.sendMessage(new TextMessage("{\"event\":\"pong\",\"id\":" + ev.path("id").asInt(0) + "}"));
			case "start" -> iniciar(c, ev.path("start"));
			case "media" -> media(c, ev.path("media"));
			case "stop" -> fecharFonte(c, ev.path("stop").path("tag").asString(""));
			default -> { }
		}
	}

	private void iniciar(Conexao c, JsonNode start) {
		String tag = start.path("tag").asString("");
		if (tag.isEmpty()) {
			return;
		}
		String endpoint = start.path("customParameters").path("endpointId").asString(null);
		int canais = start.path("mediaFormat").path("channels").asInt(2);
		c.fontes.putIfAbsent(tag, new FonteAudio(tag, endpoint, canais == 1 ? 1 : 2));
	}

	private void media(Conexao c, JsonNode m) throws IOException {
		String tag = m.path("tag").asString("");
		FonteAudio f = c.fontes.computeIfAbsent(tag, t -> new FonteAudio(t, null, 2));
		if (!permitido(c)) {
			f.descartados++;
			f.interromperLinhaDoTempo();
			return;
		}
		byte[] pacote;
		long timestamp;
		int amostras;
		try {
			pacote = Base64.getDecoder().decode(m.path("payload").asString(""));
			timestamp = Long.parseLong(m.path("timestamp").asString("x"));
			amostras = OpusPacket.amostras(pacote);
		}
		catch (IllegalArgumentException e) {
			f.descartados++; // payload, timestamp ou pacote Opus inválidos
			return;
		}
		if (f.writer == null) {
			abrirArquivo(c, f);
		}
		if (f.ultimoTimestamp != null) {
			long delta = (timestamp - f.ultimoTimestamp) & 0xFFFFFFFFL; // o timestamp RTP tem 32 bits
			if (delta == 0 || delta > 0x7FFFFFFFL) {
				return; // duplicado ou fora de ordem
			}
			long lacuna = delta - f.amostrasUltimoPacote;
			if (lacuna >= amostras / 2) {
				byte[] silencio = OpusPacket.silencioComo(pacote);
				int amostrasDoSilencio = OpusPacket.amostras(silencio);
				long quadros = Math.min(lacuna / amostrasDoSilencio, MAX_QUADROS_DE_SILENCIO);
				for (long i = 0; i < quadros; i++) {
					f.writer.adicionar(silencio, amostrasDoSilencio);
				}
			}
		}
		f.writer.adicionar(pacote, amostras);
		f.ultimoTimestamp = timestamp;
		f.amostrasUltimoPacote = amostras;
	}

	/** Consulta o portão (com cache de 1 s). Se a autorização acabou, fecha os arquivos abertos. */
	private boolean permitido(Conexao c) {
		long agora = System.nanoTime();
		if (agora - c.consultadoEm > VALIDADE_DO_PORTAO_NANOS) {
			boolean antes = c.autorizada;
			c.autorizada = captura.autorizada(c.consultaId);
			c.consultadoEm = agora;
			if (antes && !c.autorizada) {
				log.info("Audio sink: consentimento retirado ou consulta encerrada; fechando arquivos (consulta {})", c.consultaId);
				c.fontes.values().forEach(f -> fechar(f));
			}
		}
		return c.autorizada;
	}

	private void abrirArquivo(Conexao c, FonteAudio f) throws IOException {
		Path pasta = props.dir().resolve(c.consultaId.toString());
		criarPasta(pasta);
		String nome = nomeSeguro(f.tag) + "-" + System.currentTimeMillis() + ".opus";
		Path arquivo = pasta.resolve(nome);
		OutputStream out = new BufferedOutputStream(Files.newOutputStream(arquivo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
		try {
			Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------"));
		}
		catch (UnsupportedOperationException ignorado) {
			// sistema de arquivos sem POSIX
		}
		f.writer = new OggOpusWriter(out, f.canais, (int) System.nanoTime());
		AudioTransitorio registro = captura.abrir(c.consultaId, f.tag, f.endpointId, c.consultaId + "/" + nome);
		f.audioId = registro.getId();
		f.interromperLinhaDoTempo();
	}

	private static void criarPasta(Path pasta) throws IOException {
		if (Files.exists(pasta)) {
			return;
		}
		Files.createDirectories(pasta);
		try {
			Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwx------"));
		}
		catch (UnsupportedOperationException ignorado) {
			// sistema de arquivos sem POSIX
		}
	}

	static String nomeSeguro(String tag) {
		String s = tag.replaceAll("[^A-Za-z0-9._-]", "_");
		return s.isEmpty() ? "fonte" : s.substring(0, Math.min(s.length(), 64));
	}

	private void fecharFonte(Conexao c, String tag) {
		FonteAudio f = c.fontes.get(tag);
		if (f != null) {
			fechar(f);
		}
	}

	private void fechar(FonteAudio f) {
		if (f.writer == null) {
			return;
		}
		try {
			f.writer.close();
		}
		catch (IOException e) {
			log.warn("Audio sink: falha ao fechar o arquivo da fonte {}: {}", f.tag, e.getMessage());
		}
		captura.completar(f.audioId, f.writer.amostrasGravadas());
		f.writer = null;
		f.audioId = null;
		f.interromperLinhaDoTempo();
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		Conexao c = conexoes.remove(session.getId());
		if (c != null) {
			c.fontes.values().forEach(this::fechar);
			log.info("Audio sink: conexão encerrada (consulta {}, descartados {})", c.consultaId,
					c.fontes.values().stream().mapToLong(f -> f.descartados).sum());
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.warn("Audio sink: erro de transporte: {}", exception.getMessage());
	}
}
