package br.com.medilink.transcricao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Envia os jobs pendentes de tempos em tempos. Em mais de uma instância do backend o envio repetido é inofensivo (o worker deduplica). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "medilink.transcricao", name = "agendador-ativo", havingValue = "true", matchIfMissing = true)
class AgendadorDeJobs {

	private static final Logger log = LoggerFactory.getLogger(AgendadorDeJobs.class);

	private final TranscricaoJobService service;
	private final ResultadoDaTranscricaoService resultados;

	AgendadorDeJobs(TranscricaoJobService service, ResultadoDaTranscricaoService resultados) {
		this.service = service;
		this.resultados = resultados;
	}

	@Scheduled(fixedDelayString = "${medilink.transcricao.intervalo-ms:5000}", initialDelayString = "${medilink.transcricao.intervalo-ms:5000}")
	void varrer() {
		try {
			service.publicarPendentes();
		}
		catch (RuntimeException e) {
			log.warn("Transcrição: varredura falhou ({})", e.getClass().getSimpleName());
		}
		try {
			resultados.descartarPendentes(); // T13: áudio que não pôde ser apagado logo após gravar a transcrição
		}
		catch (RuntimeException e) {
			log.warn("Transcrição: varredura de descarte falhou ({})", e.getClass().getSimpleName());
		}
	}
}
