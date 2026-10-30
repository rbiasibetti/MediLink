package br.com.medilink.transcricao;

import br.com.medilink.transcricao.ResultadoDaTranscricaoService.ResultadoInvalidoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Lê o tópico de resultados do worker (T13). O offset só avança quando o listener retorna: falha de banco ou de
 * disco relança a exceção e a mesma mensagem é tentada de novo (veja {@link TranscricaoKafkaConfig}); mensagem
 * fora do contrato é descartada aqui, porque repeti-la não a corrigiria. O conteúdo nunca vai para log.
 */
@Component
@ConditionalOnProperty(prefix = "medilink.transcricao", name = "consumidor-ativo", havingValue = "true", matchIfMissing = true)
class ConsumidorDeResultados {

	private static final Logger log = LoggerFactory.getLogger(ConsumidorDeResultados.class);

	private final ResultadoDaTranscricaoService service;

	ConsumidorDeResultados(ResultadoDaTranscricaoService service) {
		this.service = service;
	}

	@KafkaListener(id = "medilink-resultados", topics = "${medilink.transcricao.topico-resultados:medilink.transcricao.resultados}", groupId = "${medilink.transcricao.grupo-consumidor:medilink-backend}")
	void receber(String mensagem) {
		try {
			service.processar(mensagem);
		}
		catch (ResultadoInvalidoException e) {
			log.warn("Transcrição: resultado descartado por estar fora do contrato ({})", e.getMessage());
		}
	}
}
