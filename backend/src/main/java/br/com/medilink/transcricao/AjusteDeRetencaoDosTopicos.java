package br.com.medilink.transcricao;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.AlterConfigsOptions;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Aplica a retenção curta (T14) também a tópicos que já existiam com o prazo padrão do broker (7 dias): o tópico de
 * resultados guarda o texto das consultas. Não derruba a subida se o broker estiver fora: avisa e segue (o consumo e o
 * envio têm as próprias repetições). Liga junto com o {@code KafkaAdmin} ({@code spring.kafka.admin.auto-create}).
 */
@Component
@ConditionalOnProperty(prefix = "spring.kafka.admin", name = "auto-create", havingValue = "true", matchIfMissing = true)
class AjusteDeRetencaoDosTopicos implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AjusteDeRetencaoDosTopicos.class);

	private final KafkaAdmin kafkaAdmin;
	private final TranscricaoProperties props;

	AjusteDeRetencaoDosTopicos(KafkaAdmin kafkaAdmin, TranscricaoProperties props) {
		this.kafkaAdmin = kafkaAdmin;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		String prazo = Long.toString(props.retencaoDosTopicos().toMillis());
		try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
			var ajustes = new java.util.HashMap<ConfigResource, java.util.Collection<AlterConfigOp>>();
			for (String topico : List.of(props.topicoJobs(), props.topicoResultados())) {
				ajustes.put(new ConfigResource(ConfigResource.Type.TOPIC, topico),
						List.of(new AlterConfigOp(new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, prazo), AlterConfigOp.OpType.SET)));
			}
			admin.incrementalAlterConfigs(ajustes, new AlterConfigsOptions().timeoutMs(5_000)).all().get(10, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			log.warn("Fila: não foi possível ajustar a retenção dos tópicos ({}); confira no broker", e.getClass().getSimpleName());
		}
	}
}
