package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import br.com.medilink.service.JitsiTokenService.Participante;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T02: o token tem o formato que o Prosody exige e só vale para a sala e o segredo certos. */
class JitsiTokenServiceTest {

	private static final String SEGREDO = "segredo-jitsi-de-teste-com-32-ou-mais-chars";
	private static final Instant AGORA = Instant.parse("2026-11-03T14:00:00Z");

	private final JitsiTokenService service = new JitsiTokenService(
			new MedilinkProperties("http://x", new MedilinkProperties.Cpf("k"),
					new MedilinkProperties.Jitsi("medilink", SEGREDO, "jitsi", "meet.jitsi", Duration.ofHours(2))),
			Clock.fixed(AGORA, ZoneOffset.UTC));

	private static JWTClaimsSet verificar(String token, String segredo) throws Exception {
		SignedJWT jwt = SignedJWT.parse(token);
		assertThat(jwt.verify(new MACVerifier(segredo.getBytes(StandardCharsets.UTF_8)))).isTrue();
		return jwt.getJWTClaimsSet();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> user(JWTClaimsSet c) {
		return (Map<String, Object>) ((Map<String, Object>) c.getClaim("context")).get("user");
	}

	@Test
	void tokenDoMedicoTemClaimsDoJitsiEEhModerador() throws Exception {
		String token = service.emitir("consulta-abc", new Participante("m1", "Dra. Ana", "ana@clinica.test", true));

		JWTClaimsSet c = verificar(token, SEGREDO);
		assertThat(c.getIssuer()).isEqualTo("medilink");
		assertThat(c.getAudience()).containsExactly("jitsi");
		assertThat(c.getSubject()).isEqualTo("meet.jitsi");
		assertThat(c.getStringClaim("room")).isEqualTo("consulta-abc");
		assertThat(c.getExpirationTime().toInstant()).isEqualTo(AGORA.plus(Duration.ofHours(2)));
		assertThat(c.getNotBeforeTime().toInstant()).isBefore(AGORA);
		assertThat(user(c)).containsEntry("moderator", true).containsEntry("affiliation", "owner")
				.containsEntry("name", "Dra. Ana");
	}

	@Test
	void tokenDoPacienteNaoModeraENaoTemAfiliacaoParaNaoPularOLobby() throws Exception {
		String token = service.emitir("consulta-abc", new Participante("p1", "Maria", null, false));

		Map<String, Object> user = user(verificar(token, SEGREDO));
		assertThat(user).containsEntry("moderator", false).doesNotContainKey("affiliation").doesNotContainKey("email");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> features(boolean moderador) throws Exception {
		String token = service.emitir("consulta-abc", new Participante("x", "Fulano", null, moderador));
		return (Map<String, Object>) ((Map<String, Object>) verificar(token, SEGREDO).getClaim("context")).get("features");
	}

	@Test
	void todoTokenDesligaOsRecursosNaoPermitidosNaSala() throws Exception {
		for (boolean moderador : new boolean[] { true, false }) {
			assertThat(features(moderador)).containsEntry("screen-sharing", false).containsEntry("send-groupchat", false)
					.containsEntry("create-polls", false).containsEntry("recording", false)
					.containsEntry("livestreaming", false).containsEntry("outbound-call", false)
					.containsEntry("sip-outbound-call", false).containsEntry("file-upload", false);
		}
	}

	@Test
	void soOMedicoPodePedirATranscricaoELigarAExportacaoDeAudio() throws Exception {
		assertThat(features(true)).containsEntry("transcription", true);
		assertThat(features(false)).containsEntry("transcription", false);
	}

	@Test
	void naoVerificaComOutroSegredo() throws Exception {
		String token = service.emitir("consulta-abc", new Participante("p1", "Maria", null, false));

		SignedJWT jwt = SignedJWT.parse(token);
		assertThat(jwt.verify(new MACVerifier("outro-segredo-com-32-ou-mais-caracteres!!".getBytes(StandardCharsets.UTF_8)))).isFalse();
	}

	@Test
	void cadaTokenEhAmarradoASuaSala() throws Exception {
		var p = new Participante("p1", "Maria", null, false);

		assertThat(verificar(service.emitir("sala-a", p), SEGREDO).getStringClaim("room")).isEqualTo("sala-a");
		assertThat(verificar(service.emitir("sala-b", p), SEGREDO).getStringClaim("room")).isEqualTo("sala-b");
	}
}
