package br.com.medilink.transcricao;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
class TranscricaoKafkaConfig {

	private static final Logger log = LoggerFactory.getLogger(TranscricaoKafkaConfig.class);

	/**
	 * Falha inesperada (banco ou disco fora do ar) repete a mesma mensagem a cada 5 s, sem limite: descartar o
	 * resultado perderia a transcrição, e o áudio só é apagado depois de ela estar gravada. Cada falha é logada só
	 * com o tipo da exceção e a posição na fila, nunca com a mensagem (que é o texto da consulta) nem a exceção
	 * inteira (o texto do erro poderia carregar conteúdo).
	 */
	@Bean
	DefaultErrorHandler tratadorDeErrosDoConsumidor() {
		DefaultErrorHandler tratador = new DefaultErrorHandler(new FixedBackOff(5_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
		tratador.setRetryListeners(new RetryListener() {
			@Override
			public void failedDelivery(ConsumerRecord<?, ?> registro, Exception falha, int tentativa) {
				Throwable causa = falha.getCause() != null ? falha.getCause() : falha;
				log.warn("Transcrição: falha ao processar o resultado {}-{}@{} ({}, tentativa {}); será repetido",
						registro.topic(), registro.partition(), registro.offset(), causa.getClass().getSimpleName(), tentativa);
			}
		});
		return tratador;
	}
}
