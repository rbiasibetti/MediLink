package br.com.medilink.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Administração (T18). O primeiro administrador nasce de {@code bootstrapEmail}/{@code bootstrapSenha} quando ainda não
 * existe nenhum (a senha vem do ambiente; em produção nunca versionada). Os demais são criados por um administrador.
 */
@ConfigurationProperties(prefix = "medilink.admin")
public record AdminProperties(
		@DefaultValue("") String bootstrapEmail,
		@DefaultValue("") String bootstrapSenha,
		@DefaultValue("30m") Duration sessaoTtl,
		@DefaultValue("5") int maxFalhas,
		@DefaultValue("15m") Duration bloqueio) {

	@Override
	public String toString() {
		return "AdminProperties[bootstrapEmail=" + bootstrapEmail + "]";
	}
}
