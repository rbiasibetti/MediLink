package br.com.medilink.admin;

import br.com.medilink.domain.TermoVersao;
import br.com.medilink.domain.TermoVersao.Status;
import br.com.medilink.repository.TermoVersaoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

/**
 * Na primeira subida depois da T18, leva os termos que viviam em arquivos (v1 e v2) para o banco: o v1 arquivado
 * (consultas antigas apontam para ele) e o v2 vigente. Depois disso, quem evolui o termo é o painel administrativo.
 */
@Component
class TermoSeed implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TermoSeed.class);
	private final TermoVersaoRepository repo;
	private final Clock clock;

	TermoSeed(TermoVersaoRepository repo, Clock clock) {
		this.repo = repo;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		if (repo.count() > 0) {
			return;
		}
		try {
			Instant agora = Instant.now(clock);
			repo.saveAndFlush(new TermoVersao("v1", ler("v1"), TermoService.hash(ler("v1")), Status.ARQUIVADO, "sistema", agora));
			repo.saveAndFlush(new TermoVersao("v2", ler("v2"), TermoService.hash(ler("v2")), Status.VIGENTE, "sistema", agora));
			log.info("Termo de consentimento: versões v1 (arquivada) e v2 (vigente) criadas a partir dos arquivos");
		}
		catch (DataIntegrityViolationException outraInstanciaGanhou) {
			// duas instâncias subindo juntas: a outra já criou
		}
	}

	private static String ler(String versao) throws IOException {
		try (var in = new ClassPathResource("termo-consentimento-" + versao + ".txt").getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
		}
	}
}
