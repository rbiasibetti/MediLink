package br.com.medilink.transcricao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Fila de transcrição (T12). Os tópicos são o contrato com o worker (veja docs/plan.md). */
@ConfigurationProperties(prefix = "medilink.transcricao")
public record TranscricaoProperties(
		@DefaultValue("medilink.transcricao.jobs") String topicoJobs,
		@DefaultValue("medilink.transcricao.resultados") String topicoResultados,
		/** Liga a rotina que envia os jobs pendentes (desligada nos testes, que chamam a varredura direto). */
		@DefaultValue("true") boolean agendadorAtivo,
		/** Liga o consumidor do tópico de resultados (desligado nos testes que não usam o broker). */
		@DefaultValue("true") boolean consumidorAtivo,
		@DefaultValue("5000") long intervaloMs,
		/** Quanto esperar um arquivo de áudio ainda "gravando" depois do fim da consulta antes de enviá-lo assim mesmo. */
		@DefaultValue("10m") Duration esperaMaximaDoAudio,
		@DefaultValue("20") int maxTentativas,
		@DefaultValue("10s") Duration timeoutDeEnvio,
		/** Quanto o broker guarda as mensagens dos dois tópicos (T14): cobre uma queda do consumidor, não mais que isso. */
		@DefaultValue("24h") Duration retencaoDosTopicos) {
}
