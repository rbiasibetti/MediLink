package br.com.medilink.medico;

import br.com.medilink.config.MedilinkProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Sessão do médico que entrou com usuário e senha (T20): JWT próprio (outra chave e audiência, que não valem para a
 * sessão presa a uma consulta, para o administrador nem para o Jitsi). Dá acesso a todas as consultas DO médico.
 */
@Service
public class LoginMedicoSessaoService {

	private static final String AUDIENCIA = "medilink-medico-login";

	public record Emitida(String token, Instant expiraEm) {
		@Override
		public String toString() {
			return "Emitida[expiraEm=" + expiraEm + "]";
		}
	}

	private final byte[] chave;
	private final LoginMedicoProperties props;
	private final Clock clock;

	public LoginMedicoSessaoService(MedilinkProperties base, LoginMedicoProperties props, Clock clock) {
		this.chave = ChavesDerivadas.derivar(base.cpf().hmacKey(), "login-medico");
		this.props = props;
		this.clock = clock;
	}

	public Emitida emitir(UUID medicoId) {
		Instant agora = Instant.now(clock);
		Instant expira = agora.plus(props.sessaoTtl());
		JWTClaimsSet c = new JWTClaimsSet.Builder().issuer("medilink").audience(AUDIENCIA).subject(medicoId.toString())
				.jwtID(UUID.randomUUID().toString()).issueTime(Date.from(agora)).expirationTime(Date.from(expira)).build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c);
			jwt.sign(new MACSigner(chave));
			return new Emitida(jwt.serialize(), expira);
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Falha ao assinar a sessão do médico", e);
		}
	}

	public Optional<UUID> verificar(String token) {
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(chave))) {
				return Optional.empty();
			}
			JWTClaimsSet c = jwt.getJWTClaimsSet();
			if (!c.getAudience().contains(AUDIENCIA) || c.getExpirationTime() == null
					|| !c.getExpirationTime().toInstant().isAfter(Instant.now(clock))) {
				return Optional.empty();
			}
			return Optional.of(UUID.fromString(c.getSubject()));
		}
		catch (ParseException | JOSEException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
