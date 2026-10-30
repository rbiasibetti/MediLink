package br.com.medilink.medico;

import br.com.medilink.config.MedilinkProperties;
import br.com.medilink.domain.AcessoMedicoLink.Modo;
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
 * Sessão do médico aberta por um link de uso único (T17): um JWT próprio (outra chave e audiência), preso a UMA consulta
 * e a um modo (SALA ou LEITURA). Só autoriza as rotas dessa consulta (ver {@link SessaoMedicoFiltro}).
 */
@Service
public class SessaoMedicoService {

	private static final String AUDIENCIA = "medilink-medico-sessao";

	public record Claims(String medicoEmail, UUID consultaId, UUID agendamentoId, Modo modo) {
	}

	private final byte[] chave;
	private final Clock clock;

	public SessaoMedicoService(MedilinkProperties props, Clock clock) {
		this.chave = ChavesDerivadas.derivar(props.cpf().hmacKey(), "sessao-medico");
		this.clock = clock;
	}

	public String emitir(String medicoEmail, UUID consultaId, UUID agendamentoId, Modo modo, Instant expira) {
		JWTClaimsSet c = new JWTClaimsSet.Builder().issuer("medilink").audience(AUDIENCIA).subject(medicoEmail)
				.claim("consulta", consultaId.toString()).claim("agendamento", agendamentoId.toString()).claim("modo", modo.name())
				.jwtID(UUID.randomUUID().toString()).issueTime(Date.from(Instant.now(clock))).expirationTime(Date.from(expira)).build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c);
			jwt.sign(new MACSigner(chave));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Falha ao assinar a sessão do médico", e);
		}
	}

	/** Vazio para qualquer token que não seja uma sessão válida e vigente. */
	public Optional<Claims> verificar(String token) {
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
			return Optional.of(new Claims(c.getSubject(), UUID.fromString(c.getStringClaim("consulta")),
					UUID.fromString(c.getStringClaim("agendamento")), Modo.valueOf(c.getStringClaim("modo"))));
		}
		catch (ParseException | JOSEException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
