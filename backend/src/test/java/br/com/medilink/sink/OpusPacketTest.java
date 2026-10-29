package br.com.medilink.sink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpusPacketTest {

	@Test
	void contaAmostrasPorModoEDuracao() {
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xFC, 1 })).isEqualTo(960);   // CELT FB 20 ms, estéreo, código 0
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xF8, 1 })).isEqualTo(960);   // CELT FB 20 ms, mono
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xF0, 1 })).isEqualTo(480);   // CELT FB 10 ms
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xE0, 1 })).isEqualTo(120);   // CELT 2,5 ms
		assertThat(OpusPacket.amostras(new byte[] { 0x00, 1 })).isEqualTo(480);          // config 0: SILK NB 10 ms
		assertThat(OpusPacket.amostras(new byte[] { 0x08, 1 })).isEqualTo(960);          // config 1: SILK NB 20 ms
		assertThat(OpusPacket.amostras(new byte[] { 0x18, 1 })).isEqualTo(2880);         // config 3: SILK NB 60 ms
		assertThat(OpusPacket.amostras(new byte[] { 0x70, 1 })).isEqualTo(480);          // config 14: híbrido FB 10 ms
		assertThat(OpusPacket.amostras(new byte[] { 0x78, 1 })).isEqualTo(960);          // config 15: híbrido FB 20 ms
	}

	@Test
	void codigoUmEDoisTemDoisQuadrosECodigoTresLeAContagem() {
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xF9, 1, 1 })).isEqualTo(2 * 960); // código 1
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xFA, 1, 1 })).isEqualTo(2 * 960); // código 2
		assertThat(OpusPacket.amostras(new byte[] { (byte) 0xFB, 3, 1 })).isEqualTo(3 * 960); // código 3, 3 quadros
	}

	@Test
	void rejeitaPacotesInvalidos() {
		assertThatThrownBy(() -> OpusPacket.amostras(new byte[0])).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpusPacket.amostras(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpusPacket.amostras(new byte[] { (byte) 0xFB })).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpusPacket.amostras(new byte[] { (byte) 0xFB, 0 })).isInstanceOf(IllegalArgumentException.class);
		// 63 quadros de 20 ms passam de 120 ms
		assertThatThrownBy(() -> OpusPacket.amostras(new byte[] { (byte) 0xFB, 63 })).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void silencioMantemModoECanaisEUsaCodigoZero() {
		byte[] silencio = OpusPacket.silencioComo(new byte[] { (byte) 0xFD, 9, 9 }); // estéreo, código 1
		assertThat(silencio).containsExactly((byte) 0xFC, (byte) 0xFF, (byte) 0xFE);
		assertThat(OpusPacket.amostras(silencio)).isEqualTo(960);
	}
}
