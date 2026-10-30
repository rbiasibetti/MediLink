package br.com.medilink.transcricao;

/** Envia a mensagem de um job à fila. Fora do ar = lança exceção; o job continua pendente e é reenviado. */
public interface PublicadorDeJobs {

	void publicar(String chave, String mensagemJson) throws Exception;
}
