package br.com.medilink.retencao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Dispara a retenção de tempos em tempos (a primeira execução, um intervalo depois da subida). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "medilink.retencao", name = "ativa", havingValue = "true", matchIfMissing = true)
class AgendadorDeRetencao {

	private final RetencaoService retencao;

	AgendadorDeRetencao(RetencaoService retencao) {
		this.retencao = retencao;
	}

	@Scheduled(fixedDelayString = "${medilink.retencao.intervalo:1h}", initialDelayString = "${medilink.retencao.intervalo:1h}")
	void executar() {
		retencao.executar();
	}
}
