package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CpfTest {

	private static MedilinkProperties props(String chave) {
		return new MedilinkProperties("http://x", new MedilinkProperties.Cpf(chave),
				new MedilinkProperties.Jitsi("a", "s".repeat(32), "jitsi", "meet.jitsi", java.time.Duration.ofHours(1)));
	}

	@Test
	void validaDigitosVerificadores() {
		assertThat(Cpf.valido("529.982.247-25")).isTrue();
		assertThat(Cpf.valido("11144477735")).isTrue();
		assertThat(Cpf.valido("529.982.247-24")).isFalse();
		assertThat(Cpf.valido("111.111.111-11")).isFalse();
		assertThat(Cpf.valido("123")).isFalse();
		assertThat(Cpf.valido(null)).isFalse();
	}

	@Test
	void hashIgnoraMascaraEEhDeterministico() {
		CpfHasher hasher = new CpfHasher(props("segredo"));

		String hash = hasher.hash("529.982.247-25");

		assertThat(hash).hasSize(64).doesNotContain("52998224725");
		assertThat(hash).isEqualTo(hasher.hash("52998224725"));
		assertThat(hasher.confere("52998224725", hash)).isTrue();
		assertThat(hasher.confere("11144477735", hash)).isFalse();
	}

	@Test
	void chavesDiferentesGeramHashesDiferentes() {
		var a = new CpfHasher(props("chave-a"));
		var b = new CpfHasher(props("chave-b"));

		assertThat(a.hash("52998224725")).isNotEqualTo(b.hash("52998224725"));
	}
}
