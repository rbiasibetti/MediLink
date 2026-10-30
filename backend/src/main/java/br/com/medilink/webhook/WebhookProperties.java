package br.com.medilink.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/** Webhook de devolução (T19). {@code modoDev} só em desenvolvimento/testes: libera HTTP e endereços locais. */
@ConfigurationProperties(prefix = "medilink.webhook")
public record WebhookProperties(
		/** Liga a rotina que envia as entregas pendentes (desligada nos testes, que chamam o despachante direto). */
		@DefaultValue("true") boolean ativo,
		@DefaultValue("false") boolean modoDev,
		@DefaultValue("5000") long intervaloMs,
		@DefaultValue("5s") Duration timeoutConexao,
		@DefaultValue("10s") Duration timeoutLeitura,
		/** Corpo máximo de uma entrega; consultas gigantes viram falha visível em vez de estourar o destino. */
		@DefaultValue("5242880") int tamanhoMaximo,
		/** Esperas entre as tentativas (a 1ª entrega é imediata); esgotada a lista, a entrega fica em FALHA. */
		@DefaultValue({"1m", "5m", "30m", "2h", "6h", "24h"}) List<Duration> recuos) {
}
