package br.com.medilink.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Criptografia em repouso (T14). {@code chave} é a chave mestra de 32 bytes em Base64 (obrigatória, sem valor padrão;
 * em produção vem de um cofre). {@code chavesAnteriores} guarda as chaves aposentadas (id → Base64) que ainda
 * precisam decifrar dados antigos depois de uma rotação. {@code aceitarTextoLegado} só serve para migrar uma base
 * criada antes da T14: lê como texto puro o que não tem o prefixo da cifra (desligado por padrão).
 */
@Validated
@ConfigurationProperties(prefix = "medilink.crypto")
public record CryptoProperties(
		@NotBlank String chave,
		@DefaultValue("k1") String chaveId,
		@DefaultValue Map<String, String> chavesAnteriores,
		@DefaultValue("false") boolean aceitarTextoLegado) {

	@Override
	public String toString() {
		return "CryptoProperties[chaveId=" + chaveId + "]";
	}
}
