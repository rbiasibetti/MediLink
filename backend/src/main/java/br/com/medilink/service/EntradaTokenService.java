package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Token de entrada: prova, por tempo curto, que o paciente passou pela porta do CPF (ou que o médico a
 * desativou) para uma sala. O T07 troca este token pelo JWT do Jitsi. Não é o JWT do Jitsi e não vale nele:
 * outra audiência e outra chave (derivada, para que um não possa ser usado como o outro).
 */
@Service
public class EntradaTokenService {

	public static final Duration VALIDADE = Duration.ofMinutes(10);
	private static final String AUDIENCIA = "medilink-entrada";

	private final byte[] chave;
	private final Clock clock;

	public EntradaTokenService(MedilinkProperties props, Clock clock) {
		this.clock = clock;
		this.chave = derivar(props.cpf().hmacKey());
	}

	public record Emitido(String token, Instant expiraEm) {
	}

	public Emitido emitir(String roomName, UUID agendamentoId) {
		Instant agora = Instant.now(clock);
		Instant expira = agora.plus(VALIDADE);
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer("medilink")
				.audience(AUDIENCIA)
				.subject(agendamentoId.toString())
				.claim("room", roomName)
				.jwtID(UUID.randomUUID().toString())
				.issueTime(Date.from(agora))
				.expirationTime(Date.from(expira))
				.build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(chave));
			return new Emitido(jwt.serialize(), expira);
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Falha ao assinar o token de entrada", e);
		}
	}

	/** Válido só se a assinatura, a audiência, a validade e a sala conferirem. */
	public boolean valido(String token, String roomName) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(chave))) {
				return false;
			}
			JWTClaimsSet c = jwt.getJWTClaimsSet();
			return c.getAudience().contains(AUDIENCIA)
					&& roomName.equals(c.getStringClaim("room"))
					&& c.getExpirationTime() != null
					&& c.getExpirationTime().toInstant().isAfter(Instant.now(clock));
		}
		catch (ParseException | JOSEException | RuntimeException e) {
			return false;
		}
	}

	private static byte[] derivar(String segredo) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal("medilink-entrada-token-v1".getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HMAC indisponível", e);
		}
	}
}
