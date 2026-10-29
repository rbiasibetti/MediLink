package br.com.medilink.sink;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Lê o arquivo de volta página por página, como um decodificador faria, e confere estrutura e CRC. */
class OggOpusWriterTest {

	@Test
	void estruturaDoArquivoEPacotesVoltamIguais() throws Exception {
		Random r = new Random(7);
		List<byte[]> entrada = new ArrayList<>();
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		try (OggOpusWriter w = new OggOpusWriter(saida, 2, 1234)) {
			for (int i = 0; i < 130; i++) {
				// inclui um pacote de 300 bytes (precisa de 2 segmentos) e um de exatamente 255 (termina com segmento 0)
				byte[] p = new byte[i == 10 ? 300 : i == 11 ? 255 : 20 + r.nextInt(80)];
				r.nextBytes(p);
				p[0] = (byte) 0xFC;
				entrada.add(p);
				w.adicionar(p, 960);
			}
			assertThat(w.amostrasGravadas()).isEqualTo(130L * 960);
		}

		List<OggLeitor.Pagina> paginas = OggLeitor.ler(saida.toByteArray());

		assertThat(paginas.get(0).tipo()).isEqualTo(0x02); // BOS
		assertThat(new String(paginas.get(0).pacotes().get(0), 0, 8, StandardCharsets.US_ASCII)).isEqualTo("OpusHead");
		assertThat(paginas.get(0).pacotes().get(0)[9]).isEqualTo((byte) 2); // canais
		assertThat(new String(paginas.get(1).pacotes().get(0), 0, 8, StandardCharsets.US_ASCII)).isEqualTo("OpusTags");
		assertThat(paginas.get(paginas.size() - 1).tipo() & 0x04).isEqualTo(0x04); // EOS
		for (int i = 0; i < paginas.size(); i++) {
			assertThat(paginas.get(i).sequencia()).isEqualTo(i);
		}
		List<byte[]> audio = new ArrayList<>();
		for (OggLeitor.Pagina p : paginas.subList(2, paginas.size())) {
			audio.addAll(p.pacotes());
		}
		assertThat(audio).hasSize(130);
		for (int i = 0; i < 130; i++) {
			assertThat(audio.get(i)).as("pacote %d", i).isEqualTo(entrada.get(i));
		}
		assertThat(paginas.get(paginas.size() - 1).granule()).isEqualTo(OggOpusWriter.PRE_SKIP + 130L * 960);
	}

	@Test
	void granuleCresceMonotonicamenteEAsPaginasTemNoMaximoUmSegundo() throws Exception {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		try (OggOpusWriter w = new OggOpusWriter(saida, 1, 1)) {
			for (int i = 0; i < 205; i++) {
				w.adicionar(new byte[] { (byte) 0xF8, 1, 2, 3 }, 960);
			}
		}
		List<OggLeitor.Pagina> paginas = OggLeitor.ler(saida.toByteArray());

		long anterior = -1;
		for (OggLeitor.Pagina p : paginas.subList(2, paginas.size())) {
			assertThat(p.granule()).isGreaterThan(anterior);
			assertThat(p.pacotes().size()).isLessThanOrEqualTo(50);
			anterior = p.granule();
		}
	}

	@Test
	void arquivoSemPacotesAindaEhValidoComEos() throws Exception {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		new OggOpusWriter(saida, 2, 5).close();

		List<OggLeitor.Pagina> paginas = OggLeitor.ler(saida.toByteArray());

		assertThat(paginas).hasSize(3); // OpusHead, OpusTags, página final vazia com EOS
		assertThat(paginas.get(2).tipo()).isEqualTo(0x04);
		assertThat(paginas.get(2).granule()).isEqualTo(OggOpusWriter.PRE_SKIP);
	}

	@Test
	void fecharDuasVezesNaoDuplicaAPaginaFinal() throws Exception {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		OggOpusWriter w = new OggOpusWriter(saida, 2, 5);
		w.adicionar(new byte[] { (byte) 0xFC, 1 }, 960);
		w.close();
		int tamanho = saida.size();

		w.close();

		assertThat(saida.size()).isEqualTo(tamanho);
	}
}
