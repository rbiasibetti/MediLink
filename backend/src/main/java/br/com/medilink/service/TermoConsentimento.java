package br.com.medilink.service;

import br.com.medilink.domain.TermoVersao;
import br.com.medilink.domain.TermoVersao.Status;
import br.com.medilink.repository.TermoVersaoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

/**
 * Termo vigente (T18): vem do banco, onde as versões são geridas pelo painel administrativo. A versão e o hash do texto
 * exibido ficam gravados junto do consentimento. O valor é guardado por poucos segundos (o termo muda raramente), e a
 * publicação o invalida na hora na própria instância.
 */
@Component
public class TermoConsentimento {

	private static final long VALIDADE_MS = 15_000;

	public record Termo(String versao, String texto, String hash) {
	}

	private final TermoVersaoRepository repo;
	private final Clock clock;
	private volatile Termo cache;
	private volatile long cacheAte;

	public TermoConsentimento(TermoVersaoRepository repo, Clock clock) {
		this.repo = repo;
		this.clock = clock;
	}

	public Termo atual() {
		long agora = clock.millis();
		Termo t = cache;
		if (t != null && agora < cacheAte) {
			return t;
		}
		TermoVersao v = repo.findByStatus(Status.VIGENTE)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não há termo de consentimento vigente."));
		t = new Termo(v.getVersao(), v.getTexto(), v.getHashTexto());
		cache = t;
		cacheAte = agora + VALIDADE_MS;
		return t;
	}

	public void invalidar() {
		cache = null;
	}
}
