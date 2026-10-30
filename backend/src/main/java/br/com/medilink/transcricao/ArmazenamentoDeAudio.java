package br.com.medilink.transcricao;

import br.com.medilink.sink.SinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Remoção dos arquivos de áudio transitórios do diretório do sink (descarte da T13 e expiração da T14). */
@Component
public class ArmazenamentoDeAudio {

	private static final Logger log = LoggerFactory.getLogger(ArmazenamentoDeAudio.class);

	private final SinkProperties sink;

	ArmazenamentoDeAudio(SinkProperties sink) {
		this.sink = sink;
	}

	/**
	 * Apaga um arquivo. O caminho vem do banco, mas é conferido mesmo assim: precisa ficar dentro da pasta da consulta
	 * no diretório do sink. Arquivo já ausente conta como apagado.
	 */
	public void apagar(UUID consultaId, String relativo) throws IOException {
		Path base = sink.dir().toAbsolutePath().normalize();
		Path pastaDaConsulta = base.resolve(consultaId.toString()).normalize();
		Path arquivo = base.resolve(relativo).normalize();
		if (!arquivo.startsWith(pastaDaConsulta) || arquivo.equals(pastaDaConsulta)) {
			throw new IOException("caminho fora da pasta da consulta");
		}
		Files.deleteIfExists(arquivo);
	}

	public void removerPastaVazia(UUID consultaId) {
		Path pasta = sink.dir().toAbsolutePath().normalize().resolve(consultaId.toString());
		try {
			Files.deleteIfExists(pasta);
		}
		catch (DirectoryNotEmptyException e) {
			log.warn("Transcrição: a pasta de áudio da consulta {} ainda tem arquivos que o sistema não registrou", consultaId);
		}
		catch (IOException e) {
			log.warn("Transcrição: não foi possível remover a pasta de áudio da consulta {} ({})", consultaId, e.getClass().getSimpleName());
		}
	}
}
