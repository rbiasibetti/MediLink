package br.com.medilink.medico;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Login do médico por senha (T20): vida da sessão e bloqueio por tentativas. */
@ConfigurationProperties(prefix = "medilink.medico-login")
public record LoginMedicoProperties(
		@DefaultValue("8h") Duration sessaoTtl,
		@DefaultValue("5") int maxFalhas,
		@DefaultValue("15m") Duration bloqueio) {
}
