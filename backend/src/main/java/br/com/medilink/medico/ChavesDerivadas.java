package br.com.medilink.medico;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** Uma chave por finalidade, derivada do segredo da aplicação: um token de um tipo não vale como o de outro. */
public final class ChavesDerivadas {

	private ChavesDerivadas() {
	}

	public static byte[] derivar(String segredo, String finalidade) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(("medilink-" + finalidade + "-v1").getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC indisponível", e);
		}
	}
}
