package br.com.medilink.transcricao;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Tópicos da fila com retenção curta (T14). O tópico de resultados carrega o <strong>texto da consulta</strong> e o de
 * jobs só referências, mas ambos deixam de ser necessários assim que são processados: manter dias de histórico seria
 * guardar dado sensível sem finalidade. O prazo só precisa cobrir uma indisponibilidade do consumidor. Em produção os
 * tópicos são criados pela operação (partições, réplicas, ACL e TLS); isto vale para dev e para o primeiro arranque.
 */
@Configuration
class TopicosDaFila {

	@Bean
	NewTopic topicoDeJobs(TranscricaoProperties props) {
		return TopicBuilder.name(props.topicoJobs()).partitions(1).replicas(1)
				.config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(props.retencaoDosTopicos().toMillis())).build();
	}

	@Bean
	NewTopic topicoDeResultados(TranscricaoProperties props) {
		return TopicBuilder.name(props.topicoResultados()).partitions(1).replicas(1)
				.config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(props.retencaoDosTopicos().toMillis())).build();
	}
}
