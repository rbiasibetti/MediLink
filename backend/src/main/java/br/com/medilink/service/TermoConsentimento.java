package br.com.medilink.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Termo vigente. A versão faz parte do texto (nome do arquivo): ao mudar o termo, crie um arquivo novo e
 * mude {@link #VERSAO}. O consentimento grava a versão que o paciente viu.
 */
@Component
public class TermoConsentimento {

	public static final String VERSAO = "v2";

	public record Termo(String versao, String texto) {
	}

	private final Termo atual;

	public TermoConsentimento() {
		try (var in = new ClassPathResource("termo-consentimento-" + VERSAO + ".txt").getInputStream()) {
			this.atual = new Termo(VERSAO, new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Termo de consentimento ausente", e);
		}
	}

	public Termo atual() {
		return atual;
	}
}
