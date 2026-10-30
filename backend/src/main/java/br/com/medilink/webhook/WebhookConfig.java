package br.com.medilink.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

@Configuration
@EnableScheduling
class WebhookConfig {

	/** TLS com as autoridades confiáveis do sistema (a JVM); certificado autoassinado não é aceito. */
	@Bean
	HttpsPostSeguro httpsPostSeguro(WebhookProperties props) throws NoSuchAlgorithmException {
		return new HttpsPostSeguro(SSLContext.getDefault(), props.modoDev(), props.timeoutConexao(), props.timeoutLeitura());
	}

	@Configuration
	@ConditionalOnProperty(prefix = "medilink.webhook", name = "ativo", havingValue = "true", matchIfMissing = true)
	static class Agendador {

		private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Agendador.class);
		private final WebhookDespachante despachante;

		Agendador(WebhookDespachante despachante) {
			this.despachante = despachante;
		}

		@Scheduled(fixedDelayString = "${medilink.webhook.intervalo-ms:5000}", initialDelayString = "${medilink.webhook.intervalo-ms:5000}")
		void enviar() {
			try {
				despachante.processarPendentes();
			}
			catch (RuntimeException e) {
				log.warn("Webhook: varredura falhou ({})", e.getClass().getSimpleName());
			}
		}
	}
}
