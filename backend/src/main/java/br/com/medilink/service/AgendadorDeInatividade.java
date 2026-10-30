package br.com.medilink.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Encerra, pelo servidor, a consulta cujo médico parou de dar sinal (aba fechada, queda de internet). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "medilink.consulta", name = "verificar-inatividade", havingValue = "true", matchIfMissing = true)
class AgendadorDeInatividade {

	private static final Logger log = LoggerFactory.getLogger(AgendadorDeInatividade.class);

	private final ConsultaService consultas;

	AgendadorDeInatividade(ConsultaService consultas) {
		this.consultas = consultas;
	}

	@Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
	void verificar() {
		try {
			int n = consultas.encerrarInativas();
			if (n > 0) {
				log.info("Consultas encerradas por inatividade do médico: {}", n);
			}
		}
		catch (RuntimeException e) {
			log.warn("Verificação de inatividade falhou ({})", e.getClass().getSimpleName());
		}
	}
}
