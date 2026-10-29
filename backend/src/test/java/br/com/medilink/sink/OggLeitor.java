package br.com.medilink.sink;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lê um arquivo Ogg página por página, como um decodificador faria, e confere a assinatura e o CRC de cada uma. */
final class OggLeitor {

	private OggLeitor() {
	}

	record Pagina(int tipo, long granule, int sequencia, List<byte[]> pacotes) {
	}

	static List<Pagina> ler(byte[] arquivo) {
		List<Pagina> paginas = new ArrayList<>();
		int pos = 0;
		while (pos < arquivo.length) {
			ByteBuffer b = ByteBuffer.wrap(arquivo, pos, arquivo.length - pos).order(ByteOrder.LITTLE_ENDIAN);
			assertThat(new String(arquivo, pos, 4, StandardCharsets.US_ASCII)).isEqualTo("OggS");
			int tipo = arquivo[pos + 5] & 0xFF;
			long granule = b.getLong(pos + 6);
			int sequencia = b.getInt(pos + 18);
			int crcGravado = b.getInt(pos + 22);
			int nSeg = arquivo[pos + 26] & 0xFF;
			int tamanhoCorpo = 0;
			for (int i = 0; i < nSeg; i++) {
				tamanhoCorpo += arquivo[pos + 27 + i] & 0xFF;
			}
			int tamanhoPagina = 27 + nSeg + tamanhoCorpo;
			byte[] pagina = Arrays.copyOfRange(arquivo, pos, pos + tamanhoPagina);
			ByteBuffer.wrap(pagina).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0);
			assertThat(OggOpusWriter.crc(pagina)).as("CRC da página %d", sequencia).isEqualTo(crcGravado);

			List<byte[]> pacotes = new ArrayList<>();
			ByteArrayOutputStream atual = new ByteArrayOutputStream();
			int corpo = pos + 27 + nSeg;
			for (int i = 0; i < nSeg; i++) {
				int s = arquivo[pos + 27 + i] & 0xFF;
				atual.write(arquivo, corpo, s);
				corpo += s;
				if (s < 255) {
					pacotes.add(atual.toByteArray());
					atual.reset();
				}
			}
			paginas.add(new Pagina(tipo, granule, sequencia, pacotes));
			pos += tamanhoPagina;
		}
		return paginas;
	}

	/** Pacotes de áudio (depois de OpusHead e OpusTags), na ordem. */
	static List<byte[]> pacotesDeAudio(byte[] arquivo) {
		List<Pagina> paginas = ler(arquivo);
		List<byte[]> audio = new ArrayList<>();
		for (Pagina p : paginas.subList(2, paginas.size())) {
			audio.addAll(p.pacotes());
		}
		return audio;
	}
}
