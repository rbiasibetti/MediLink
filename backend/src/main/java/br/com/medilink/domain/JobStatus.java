package br.com.medilink.domain;

public enum JobStatus {
	/** Criado ao encerrar a consulta; aguardando os arquivos de áudio fecharem e o envio à fila. */
	PENDENTE,
	ENFILEIRADO,
	/** A consulta terminou sem nenhum áudio capturado (sem consentimento, por exemplo): nada a transcrever. */
	SEM_AUDIO,
	/** Desistiu de enviar depois de muitas tentativas (broker fora do ar); o áudio é mantido. */
	FALHA_NO_ENVIO,
	/** O worker devolveu o texto e a {@code Transcricao} foi gravada; falta apagar o áudio (repetido até conseguir). */
	TRANSCRITO,
	/** Transcrição gravada e áudio apagado: o fim do caminho feliz. */
	CONCLUIDO,
	/** O worker não conseguiu transcrever; o áudio é mantido. Um novo resultado do worker ainda pode concluir o job. */
	FALHA_NO_WORKER
}
