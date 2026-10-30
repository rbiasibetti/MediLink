package br.com.medilink.admin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/** TOTP (RFC 6238): HMAC-SHA1, passo de 30 s, 6 dígitos, segredo em Base32 — compatível com os aplicativos autenticadores. */
public final class Totp {

	private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
	private static final SecureRandom RANDOM = new SecureRandom();

	private Totp() {
	}

	public static String novoSegredo() {
		byte[] bruto = new byte[20];
		RANDOM.nextBytes(bruto);
		return base32(bruto);
	}

	public static long passo(long epochSegundos) {
		return epochSegundos / 30;
	}

	/**
	 * Confere o código nos passos vizinhos (tolera ±1 passo de diferença de relógio) e devolve o passo que bateu, ou -1.
	 * Só aceita passos MAIORES que {@code ultimoPassoUsado}: o mesmo código não vale duas vezes.
	 */
	public static long validar(String segredoBase32, String codigo, long passoAtual, Long ultimoPassoUsado) {
		if (codigo == null || !codigo.matches("\\d{6}")) {
			return -1;
		}
		long encontrado = -1;
		for (long p = passoAtual - 1; p <= passoAtual + 1; p++) {
			if (constanteIgual(gerar(segredoBase32, p), codigo) && (ultimoPassoUsado == null || p > ultimoPassoUsado)) {
				encontrado = p;
			}
		}
		return encontrado;
	}

	public static String gerar(String segredoBase32, long passo) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(decodificarBase32(segredoBase32), "HmacSHA1"));
			byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(passo).array());
			int o = h[h.length - 1] & 0x0f;
			int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
			return String.format("%06d", bin % 1_000_000);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA1 indisponível", e);
		}
	}

	public static String uri(String emissor, String conta, String segredo) {
		return "otpauth://totp/" + java.net.URLEncoder.encode(emissor + ":" + conta, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
				+ "?secret=" + segredo + "&issuer=" + java.net.URLEncoder.encode(emissor, java.nio.charset.StandardCharsets.UTF_8) + "&digits=6&period=30";
	}

	private static boolean constanteIgual(String a, String b) {
		return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.US_ASCII), b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
	}

	static String base32(byte[] dados) {
		StringBuilder sb = new StringBuilder();
		int buffer = 0, bits = 0;
		for (byte b : dados) {
			buffer = (buffer << 8) | (b & 0xff);
			bits += 8;
			while (bits >= 5) {
				sb.append(ALFABETO.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			sb.append(ALFABETO.charAt((buffer << (5 - bits)) & 31));
		}
		return sb.toString();
	}

	static byte[] decodificarBase32(String texto) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		int buffer = 0, bits = 0;
		for (char c : texto.toUpperCase().toCharArray()) {
			int v = ALFABETO.indexOf(c);
			if (v < 0) {
				continue;
			}
			buffer = (buffer << 5) | v;
			bits += 5;
			if (bits >= 8) {
				out.write((buffer >> (bits - 8)) & 0xff);
				bits -= 8;
			}
		}
		return out.toByteArray();
	}
}
