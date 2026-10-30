package br.com.medilink.integracao;

import br.com.medilink.domain.Integrador;
import br.com.medilink.integracao.ApiKeyService.Criada;
import br.com.medilink.repository.IntegradorRepository;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Um sistema integrado de teste com uma API key de todos os escopos (nome único por instância). */
public final class SistemaIntegrado {

	public final Integrador integrador;
	public final Criada key;

	public SistemaIntegrado(IntegradorRepository integradores, ApiKeyService keys) {
		this(integradores, keys, Set.of("agendamento:escrever", "agendamento:ler", "sala:iniciar"));
	}

	public SistemaIntegrado(IntegradorRepository integradores, ApiKeyService keys, Set<String> escopos) {
		this.integrador = integradores.save(new Integrador("Sistema de teste " + UUID.randomUUID(), Instant.now()));
		this.key = keys.criar("teste", integrador.getId(), "key de teste", escopos, null);
	}

	/** Valor do cabeçalho X-API-Key. */
	public String chave() {
		return key.chave();
	}
}
