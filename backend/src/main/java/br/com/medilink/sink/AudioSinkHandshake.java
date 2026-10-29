package br.com.medilink.sink;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Autentica o JVB no handshake: o Jicofo manda "Authorization: Bearer {token}" (SINK_TOKEN do Jitsi) e a sala vem
 * no parâmetro "sala", que o módulo Prosody do MediLink acrescenta à URL (metadado transcription.urlParams, que
 * clientes não conseguem escrever). Sem o token certo não há conexão: o segredo não é do paciente nem do médico.
 */
final class AudioSinkHandshake implements HandshakeInterceptor {

	static final String SALA = "sala";
	static final String MEETING_ID = "meetingId";
	private static final Pattern NOME_DE_SALA = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final byte[] esperado;

	AudioSinkHandshake(SinkProperties props) {
		this.esperado = ("Bearer " + props.token()).getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
			Map<String, Object> atributos) {
		String auth = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (auth == null || !MessageDigest.isEqual(esperado, auth.getBytes(StandardCharsets.UTF_8))) {
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return false;
		}
		String sala = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst(SALA);
		if (sala == null || !NOME_DE_SALA.matcher(sala).matches()) {
			response.setStatusCode(HttpStatus.BAD_REQUEST);
			return false;
		}
		String caminho = request.getURI().getPath();
		atributos.put(SALA, sala);
		atributos.put(MEETING_ID, caminho.substring(caminho.lastIndexOf('/') + 1));
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception ex) {
	}
}
