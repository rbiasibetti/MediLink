package br.com.medilink.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "medilink")
public record MedilinkProperties(@NotBlank String publicBaseUrl, @NotNull @Valid Cpf cpf,
		@NotNull @Valid Jitsi jitsi) {

	public record Cpf(@NotBlank String hmacKey) {
	}

	/**
	 * Deve espelhar a configuração do Jitsi (infra/jitsi/.env): appId = JWT_APP_ID e JWT_ACCEPTED_ISSUERS,
	 * appSecret = JWT_APP_SECRET, audience = JWT_ACCEPTED_AUDIENCES, xmppDomain = XMPP_DOMAIN.
	 * HS256 exige segredo de pelo menos 256 bits (32 caracteres).
	 */
	public record Jitsi(@NotBlank String appId, @NotBlank @Size(min = 32) String appSecret,
			@NotBlank String audience, @NotBlank String xmppDomain, @NotNull Duration tokenTtl) {

		@Override
		public String toString() {
			return "Jitsi[appId=" + appId + ", audience=" + audience + ", xmppDomain=" + xmppDomain + "]";
		}
	}
}
