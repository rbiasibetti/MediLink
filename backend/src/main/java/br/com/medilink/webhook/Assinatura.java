package br.com.medilink.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Assinatura do webhook: {@code X-MediLink-Signature: t=<epoch>,v1=<hex(HMAC-SHA256(segredo, t + "." + corpo))>}.
 * O carimbo de tempo entra na assinatura: o receptor recusa mensagens antigas (proteção contra repetição) e confere o
 * valor em tempo constante. Mesmo esquema de assinatura usado por outros provedores de webhook, para facilitar o receptor.
 */
public final class Assinatura {

	private Assinatura() {
	}

	public static String cabecalho(String segredo, long epochSegundos, byte[] corpo) {
		return "t=" + epochSegundos + ",v1=" + hmac(segredo, epochSegundos, corpo);
	}

	/** Para o receptor (e para os testes): confere assinatura e idade máxima da mensagem. */
	public static boolean valida(String segredo, String cabecalho, byte[] corpo, long agoraSegundos, long toleranciaSegundos) {
		try {
			String[] p = cabecalho.split(",");
			long t = Long.parseLong(p[0].substring(2));
			String v1 = p[1].substring(3);
			return Math.abs(agoraSegundos - t) <= toleranciaSegundos
					&& MessageDigest.isEqual(v1.getBytes(StandardCharsets.US_ASCII), hmac(segredo, t, corpo).getBytes(StandardCharsets.US_ASCII));
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static String hmac(String segredo, long t, byte[] corpo) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((t + ".").getBytes(StandardCharsets.US_ASCII));
			return HexFormat.of().formatHex(mac.doFinal(corpo));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC indisponível", e);
		}
	}
}
