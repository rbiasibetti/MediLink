package br.com.medilink.sink;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Configuração do audio sink (T11). {@code token} é o segredo que o Jicofo envia no cabeçalho Authorization
 * (jicofo.transcription.http-headers) e que o sink exige no handshake; no Jitsi vem de SINK_TOKEN.
 * {@code dir} é o diretório dos arquivos transitórios, que deve ser cifrado em repouso (T14).
 */
@Validated
@ConfigurationProperties(prefix = "medilink.sink")
public record SinkProperties(@NotBlank @Size(min = 32) String token, @NotNull Path dir) {

	@Override
	public String toString() {
		return "SinkProperties[dir=" + dir + "]";
	}
}
