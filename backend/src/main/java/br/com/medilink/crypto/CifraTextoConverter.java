package br.com.medilink.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Cifra a coluna ao gravar e decifra ao ler (T14). Usada nos textos da transcrição. */
@Component
@Converter
public class CifraTextoConverter implements AttributeConverter<String, String> {

	private final Cifrador cifrador;

	public CifraTextoConverter(Cifrador cifrador) {
		this.cifrador = cifrador;
	}

	@Override
	public String convertToDatabaseColumn(String claro) {
		return claro == null ? null : cifrador.cifrarTexto(claro);
	}

	@Override
	public String convertToEntityAttribute(String armazenado) {
		return armazenado == null ? null : cifrador.decifrarTexto(armazenado);
	}
}
