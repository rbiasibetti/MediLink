package br.com.medilink.webhook;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP mínimo e restritivo para o webhook (T19). Faz exatamente um POST, e por isso foi escrito à mão em vez de
 * usar um cliente genérico: o objetivo é controlar cada ponto da defesa contra SSRF.
 * <ul>
 * <li>O DNS é resolvido AQUI, todos os endereços precisam ser públicos ({@link EnderecoPublico}) e a conexão vai para
 * o IP já validado (sem nova resolução: não há "DNS rebinding" entre a checagem e o uso);</li>
 * <li>HTTPS com certificado verificado contra o nome do host (SNI + verificação de nome), TLS 1.2 ou 1.3;</li>
 * <li>nenhum redirecionamento é seguido (3xx é falha), timeouts de conexão e de leitura, corpo da resposta ignorado;</li>
 * <li>cabeçalhos são só os passados pelo sistema (nunca vindos do agendamento), sem quebra de linha.</li>
 * </ul>
 * {@code modoDev} (só em desenvolvimento e testes) permite HTTP e endereços locais; produção recusa subir com isso ligado.
 */
public class HttpsPostSeguro {

	/** Falha de rede, de TLS ou de política. {@code permanente} = repetir não ajuda (ex.: destino bloqueado). */
	public static class FalhaDeEnvio extends IOException {
		private final boolean permanente;
		private final String tipo;

		public FalhaDeEnvio(String tipo, boolean permanente, Throwable causa) {
			super(tipo, causa);
			this.tipo = tipo;
			this.permanente = permanente;
		}

		public boolean permanente() {
			return permanente;
		}

		/** Código curto e fixo, seguro para guardar e mostrar (nunca a mensagem da exceção). */
		public String tipo() {
			return tipo;
		}
	}

	public record Resposta(int status) {
	}

	private final SSLContext ssl;
	private final boolean modoDev;
	private final Duration conexao;
	private final Duration leitura;

	public HttpsPostSeguro(SSLContext ssl, boolean modoDev, Duration conexao, Duration leitura) {
		this.ssl = ssl;
		this.modoDev = modoDev;
		this.conexao = conexao;
		this.leitura = leitura;
	}

	public Resposta postar(URI destino, byte[] corpo, Map<String, String> cabecalhos) throws FalhaDeEnvio {
		boolean https = "https".equalsIgnoreCase(destino.getScheme());
		if (!https && !(modoDev && "http".equalsIgnoreCase(destino.getScheme()))) {
			throw new FalhaDeEnvio("ESQUEMA_PROIBIDO", true, null);
		}
		String host = destino.getHost();
		if (host == null || destino.getRawUserInfo() != null) {
			throw new FalhaDeEnvio("DESTINO_INVALIDO", true, null);
		}
		int porta = destino.getPort() > 0 ? destino.getPort() : (https ? 443 : 80);
		InetAddress alvo = resolver(host);
		String caminho = (destino.getRawPath() == null || destino.getRawPath().isEmpty() ? "/" : destino.getRawPath())
				+ (destino.getRawQuery() == null ? "" : "?" + destino.getRawQuery());

		try (Socket cru = new Socket()) {
			cru.connect(new InetSocketAddress(alvo, porta), (int) conexao.toMillis()); // IP já validado: não resolve de novo
			cru.setSoTimeout((int) leitura.toMillis());
			Socket socket = cru;
			if (https) {
				SSLSocket s = (SSLSocket) ssl.getSocketFactory().createSocket(cru, host, porta, true);
				SSLParameters p = s.getSSLParameters();
				p.setEndpointIdentificationAlgorithm("HTTPS"); // confere o nome do certificado com o host
				p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
				if (!ehLiteralIp(host)) {
					p.setServerNames(List.of(new SNIHostName(host)));
				}
				s.setSSLParameters(p);
				s.startHandshake();
				socket = s;
			}
			escrever(socket.getOutputStream(), host, porta, https, caminho, corpo, cabecalhos);
			return new Resposta(lerStatus(socket.getInputStream()));
		}
		catch (javax.net.ssl.SSLException e) {
			throw new FalhaDeEnvio("TLS_RECUSADO", false, e); // certificado inválido pode ser corrigido pelo destino: tenta de novo mais tarde
		}
		catch (java.net.SocketTimeoutException e) {
			throw new FalhaDeEnvio("TEMPO_ESGOTADO", false, e);
		}
		catch (IOException e) {
			if (e instanceof FalhaDeEnvio f) {
				throw f;
			}
			throw new FalhaDeEnvio("ERRO_DE_REDE", false, e);
		}
	}

	private InetAddress resolver(String host) throws FalhaDeEnvio {
		try {
			InetAddress[] todos = InetAddress.getAllByName(host);
			if (!modoDev) {
				for (InetAddress a : todos) { // um único endereço proibido invalida o host inteiro
					if (!EnderecoPublico.permitido(a)) {
						throw new FalhaDeEnvio("DESTINO_BLOQUEADO", true, null);
					}
				}
			}
			return todos[0];
		}
		catch (UnknownHostException e) {
			throw new FalhaDeEnvio("DNS_FALHOU", false, e);
		}
	}

	private static boolean ehLiteralIp(String host) {
		return host.matches("[0-9.]+") || host.contains(":");
	}

	private static void escrever(OutputStream out, String host, int porta, boolean https, String caminho, byte[] corpo, Map<String, String> cab) throws IOException {
		Map<String, String> h = new LinkedHashMap<>();
		boolean padrao = https ? porta == 443 : porta == 80;
		h.put("Host", padrao ? host : host + ":" + porta);
		h.putAll(cab);
		h.put("Content-Length", Integer.toString(corpo.length));
		h.put("Connection", "close");
		StringBuilder sb = new StringBuilder("POST ").append(caminho).append(" HTTP/1.1\r\n");
		h.forEach((k, v) -> {
			if (k.contains("\r") || k.contains("\n") || v.contains("\r") || v.contains("\n")) {
				throw new IllegalArgumentException("cabeçalho inválido");
			}
			sb.append(k).append(": ").append(v).append("\r\n");
		});
		sb.append("\r\n");
		out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.write(corpo);
		out.flush();
	}

	/** Lê só a linha de status (e descarta o resto até 16 KB): o corpo da resposta não interessa. */
	private static int lerStatus(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int b;
		while (buf.size() < 16 * 1024 && (b = in.read()) >= 0) {
			if (b == '\n') {
				break; // fim da primeira linha
			}
			buf.write(b);
		}
		String linha = buf.toString(StandardCharsets.ISO_8859_1).strip();
		String[] partes = linha.split(" ", 3);
		if (partes.length < 2 || !partes[0].startsWith("HTTP/")) {
			throw new FalhaDeEnvio("RESPOSTA_INVALIDA", false, null);
		}
		try {
			return Integer.parseInt(partes[1]);
		}
		catch (NumberFormatException e) {
			throw new FalhaDeEnvio("RESPOSTA_INVALIDA", false, e);
		}
	}
}
