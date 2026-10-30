package br.com.medilink.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Destino do webhook = URL base cadastrada pelo administrador + caminho e parâmetros enviados no agendamento (T19).
 * O cliente do agendamento só controla o que vem depois da base, e mesmo isso é validado de forma restritiva: o objetivo
 * é que ele nunca consiga mudar o host, o esquema ou a porta, nem sair do prefixo cadastrado.
 */
public final class WebhookDestino {

	/** Lista branca: letras, dígitos, "unreserved", "sub-delims" e ":" "/" "?" "%". Fora "@", "#", "\" e espaço. */
	private static final Pattern PERMITIDO = Pattern.compile("[A-Za-z0-9\\-._~!$&'()*+,;=:/?%]{1,500}");
	private static final int MAX_PARAMETROS = 20;

	private WebhookDestino() {
	}

	/** Valida e devolve o caminho com parâmetros, ou {@code null} se vazio. Lança 422 se for suspeito. */
	public static String validarCaminho(String bruto) {
		if (bruto == null || bruto.isBlank()) {
			return null;
		}
		if (!PERMITIDO.matcher(bruto).matches() || !bruto.startsWith("/") || bruto.startsWith("//")) {
			throw recusado("O caminho do webhook precisa começar por uma única \"/\" e usar só caracteres permitidos (use %40 para @).");
		}
		int q = bruto.indexOf('?');
		String caminho = q < 0 ? bruto : bruto.substring(0, q);
		String consulta = q < 0 ? "" : bruto.substring(q + 1);
		if (consulta.indexOf('?') >= 0) {
			throw recusado("O caminho do webhook tem \"?\" repetido.");
		}
		for (String segmento : caminho.substring(1).split("/", -1)) {
			String s = decodificar(segmento);
			if (segmento.isEmpty() && !caminho.equals("/")) {
				throw recusado("O caminho do webhook não pode ter segmentos vazios.");
			}
			if (s.equals(".") || s.equals("..") || s.contains("/") || s.contains("\\") || temControle(s)) {
				throw recusado("O caminho do webhook tem um segmento inválido.");
			}
		}
		if (!consulta.isEmpty()) {
			String[] pares = consulta.split("&", -1);
			if (pares.length > MAX_PARAMETROS) {
				throw recusado("Parâmetros demais no webhook.");
			}
			for (String par : pares) {
				if (temControle(decodificar(par))) {
					throw recusado("O webhook tem parâmetro inválido.");
				}
			}
		}
		return bruto;
	}

	/** Valida a URL base cadastrada pelo administrador. {@code permitirHttp} só em desenvolvimento. */
	public static URI validarBase(String bruto, boolean permitirHttp) {
		if (bruto == null || bruto.isBlank() || bruto.length() > 500) {
			throw recusado("URL base do webhook ausente ou grande demais.");
		}
		try {
			URI u = new URI(bruto.strip());
			boolean https = "https".equalsIgnoreCase(u.getScheme());
			boolean http = "http".equalsIgnoreCase(u.getScheme());
			if (!(https || (permitirHttp && http)) || u.getHost() == null || u.getRawUserInfo() != null
					|| u.getRawQuery() != null || u.getRawFragment() != null || u.isOpaque()) {
				throw recusado("A URL base do webhook precisa ser HTTPS, sem usuário, parâmetros nem fragmento.");
			}
			String raw = u.getRawPath() == null ? "" : u.getRawPath();
			if (raw.contains("..") || raw.contains("\\") || raw.contains("//")) {
				throw recusado("A URL base do webhook tem caminho inválido.");
			}
			return u;
		}
		catch (URISyntaxException e) {
			throw recusado("A URL base do webhook é inválida.");
		}
	}

	/** Base + destino. Confere que esquema, host e porta não mudaram e que o caminho continua sob o prefixo da base. */
	public static URI compor(URI base, String destino) {
		String prefixo = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
		String porta = base.getPort() < 0 ? "" : ":" + base.getPort();
		String texto = base.getScheme() + "://" + base.getHost() + porta + prefixo + (destino == null ? "" : destino);
		try {
			URI final_ = new URI(texto);
			boolean mesmo = base.getScheme().equalsIgnoreCase(final_.getScheme())
					&& base.getHost().equalsIgnoreCase(final_.getHost()) && base.getPort() == final_.getPort()
					&& final_.getRawUserInfo() == null && final_.getRawFragment() == null;
			String caminhoFinal = final_.getRawPath() == null ? "" : final_.getRawPath();
			if (!mesmo || !(caminhoFinal.equals(prefixo) || caminhoFinal.startsWith(prefixo + "/") || (prefixo.isEmpty()))) {
				throw recusado("O destino do webhook sai da URL base cadastrada.");
			}
			return final_;
		}
		catch (URISyntaxException e) {
			throw recusado("O destino do webhook é inválido.");
		}
	}

	private static String decodificar(String s) {
		try {
			return URLDecoder.decode(s, StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException e) {
			throw recusado("O webhook tem codificação de % inválida.");
		}
	}

	private static boolean temControle(String s) {
		return s.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
	}

	private static ResponseStatusException recusado(String motivo) {
		return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, motivo);
	}
}
