package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emite o JWT que o Prosody (mod_auth_token) do Jitsi exige para entrar numa sala. Cada token vale para
 * uma única sala (claim {@code room}) e expira.
 *
 * <p>O médico recebe {@code moderator=true} e {@code affiliation=owner}; o módulo {@code token_affiliation} do
 * Prosody (habilitado em infra/jitsi/.env) converte isso em owner/moderator na sala. O paciente não recebe
 * nenhuma das duas claims e entra como participante comum. Atenção: a sala do Jitsi nasce aberta; o lobby só
 * passa a barrar quando é ligado (T08).
 */
@Service
public class JitsiTokenService {

	/** Tolerância de relógio entre este serviço e o Prosody. */
	private static final Duration SKEW = Duration.ofSeconds(30);

	/**
	 * Recursos do Jitsi desligados para todos, médico inclusive. Vão no JWT (assinado por nós, o participante não
	 * altera). O Prosody impõe enquetes, gravação, transmissão, ligações e envio de arquivos; compartilhamento
	 * de tela e chat são lidos pelo cliente (a interface também os remove da barra, ver infra/jitsi/custom-config.js).
	 * Exceção: o médico (moderador) recebe {@code transcription=true}, permissão que o Prosody exige para ligar a
	 * exportação do áudio ao audio sink (T11). O portão do consentimento, porém, é do sink.
	 */
	static final Map<String, Object> FEATURES = featuresDo(false);

	private static Map<String, Object> featuresDo(boolean moderador) {
		return Map.ofEntries(
			Map.entry("screen-sharing", false),
			Map.entry("send-groupchat", false),
			Map.entry("create-polls", false),
			Map.entry("recording", false),
			Map.entry("livestreaming", false),
			Map.entry("transcription", moderador),
			Map.entry("outbound-call", false),
			Map.entry("sip-outbound-call", false),
			Map.entry("file-upload", false));
	}

	public record Participante(String id, String nome, String email, boolean moderador) {
	}

	private final MedilinkProperties.Jitsi cfg;
	private final MACSigner signer;
	private final Clock clock;

	public JitsiTokenService(MedilinkProperties props, Clock clock) {
		this.cfg = props.jitsi();
		this.clock = clock;
		try {
			this.signer = new MACSigner(cfg.appSecret().getBytes(StandardCharsets.UTF_8));
		}
		catch (JOSEException e) {
			throw new IllegalStateException("medilink.jitsi.app-secret inválido para HS256", e);
		}
	}

	public String emitir(String roomName, Participante participante) {
		Instant agora = Instant.now(clock);

		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", participante.id());
		user.put("name", participante.nome());
		if (participante.email() != null) {
			user.put("email", participante.email());
		}
		user.put("moderator", participante.moderador());
		if (participante.moderador()) {
			user.put("affiliation", "owner");
		}

		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.issuer(cfg.appId())
				.audience(cfg.audience())
				.subject(cfg.xmppDomain())
				.claim("room", roomName)
				.notBeforeTime(Date.from(agora.minus(SKEW)))
				.expirationTime(Date.from(agora.plus(cfg.tokenTtl())))
				.claim("context", Map.of("user", user, "features", featuresDo(participante.moderador())))
				.build();

		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
			jwt.sign(signer);
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Falha ao assinar o JWT do Jitsi", e);
		}
	}
}
