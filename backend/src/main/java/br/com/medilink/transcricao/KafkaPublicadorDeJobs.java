package br.com.medilink.transcricao;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Publica no Kafka (Redpanda em desenvolvimento, mesma API). A chave é a consulta, para manter a ordem por consulta. */
@Component
class KafkaPublicadorDeJobs implements PublicadorDeJobs {

	private final KafkaTemplate<String, String> kafka;
	private final TranscricaoProperties props;

	KafkaPublicadorDeJobs(KafkaTemplate<String, String> kafka, TranscricaoProperties props) {
		this.kafka = kafka;
		this.props = props;
	}

	@Override
	public void publicar(String chave, String mensagemJson) throws Exception {
		kafka.send(props.topicoJobs(), chave, mensagemJson).get(props.timeoutDeEnvio().toMillis(), TimeUnit.MILLISECONDS);
	}
}
