package br.com.medilink.service;

import br.com.medilink.config.MedilinkProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntradaTokenServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-03T14:00:00Z");

	private static EntradaTokenService servico(String chaveCpf, Instant agora) {
		var props = new MedilinkProperties("http://x", new MedilinkProperties.Cpf(chaveCpf),
				new MedilinkProperties.Jitsi("a", "s".repeat(32), "jitsi", "meet.jitsi", Duration.ofHours(1)));
		return new EntradaTokenService(props, Clock.fixed(agora, ZoneOffset.UTC));
	}

	@Test
	void validaNaSalaCertaDentroDoPrazo() {
		var emitido = servico("k", T0).emitir("sala-a", UUID.randomUUID());

		assertThat(emitido.expiraEm()).isEqualTo(T0.plus(EntradaTokenService.VALIDADE));
		assertThat(servico("k", T0.plusSeconds(60)).valido(emitido.token(), "sala-a")).isTrue();
	}

	@Test
	void expiraAposAValidade() {
		var emitido = servico("k", T0).emitir("sala-a", UUID.randomUUID());

		assertThat(servico("k", T0.plus(EntradaTokenService.VALIDADE).plusSeconds(1)).valido(emitido.token(), "sala-a")).isFalse();
	}

	@Test
	void naoValeEmOutraSalaNemComOutraChaveNemLixo() {
		var emitido = servico("k", T0).emitir("sala-a", UUID.randomUUID());

		assertThat(servico("k", T0).valido(emitido.token(), "sala-b")).isFalse();
		assertThat(servico("outra-chave", T0).valido(emitido.token(), "sala-a")).isFalse();
		assertThat(servico("k", T0).valido("lixo", "sala-a")).isFalse();
	}

	@Test
	void naoEhAceitoComoJwtDoJitsi() {
		// O token de entrada tem outra audiência e chave derivada: não pode virar credencial do Jitsi.
		var emitido = servico("k", T0).emitir("sala-a", UUID.randomUUID());

		assertThat(emitido.token()).doesNotContain("meet.jitsi");
		assertThat(servico("s".repeat(32), T0).valido(emitido.token(), "sala-a")).isFalse();
	}
}
