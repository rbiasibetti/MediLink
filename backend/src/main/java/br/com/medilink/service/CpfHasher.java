package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 do CPF com segredo do servidor. O CPF tem só 11 dígitos, então um hash simples seria
 * revertido por força bruta; sem a chave o HMAC não é.
 */
@Component
public class CpfHasher {

	private static final String ALGORITMO = "HmacSHA256";

	private final SecretKeySpec chave;

	public CpfHasher(MedilinkProperties props) {
		this.chave = new SecretKeySpec(props.cpf().hmacKey().getBytes(StandardCharsets.UTF_8), ALGORITMO);
	}

	public String hash(String cpf) {
		try {
			Mac mac = Mac.getInstance(ALGORITMO);
			mac.init(chave);
			return HexFormat.of().formatHex(mac.doFinal(Cpf.normalizar(cpf).getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC indisponível", e);
		}
	}

	/** Comparação em tempo constante do CPF informado contra o hash armazenado. */
	public boolean confere(String cpfInformado, String hashArmazenado) {
		return MessageDigest.isEqual(
				hash(cpfInformado).getBytes(StandardCharsets.UTF_8),
				hashArmazenado.getBytes(StandardCharsets.UTF_8));
	}
}
