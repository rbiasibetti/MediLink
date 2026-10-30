package br.com.medilink.integracao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Limites da API de integração (T16/T17). */
@ConfigurationProperties(prefix = "medilink.integracao")
public record IntegracaoProperties(
		@DefaultValue("120") int limitePorMinuto,
		/** Até onde no futuro um agendamento pode ser marcado. */
		@DefaultValue("366d") Duration horizonte,
		/** Notificações por e-mail por key e por minuto (a plataforma não vira relay de spam). */
		@DefaultValue("30") int emailsPorMinuto) {
}
