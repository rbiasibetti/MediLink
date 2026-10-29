package br.com.medilink.sink;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Grava pacotes Opus em um arquivo Ogg Opus (RFC 7845), que ffmpeg e faster-whisper leem direto. Os pacotes
 * já chegam codificados (não há decodificação aqui); só se empacotam em páginas Ogg com CRC.
 */
final class OggOpusWriter implements Closeable {

	/** Pré-atraso padrão do libopus a 48 kHz. */
	static final int PRE_SKIP = 312;
	private static final int MAX_PACOTES_POR_PAGINA = 50; // ~1 s de áudio de 20 ms
	private static final int MAX_SEGMENTOS = 255;
	private static final int[] CRC = tabelaCrc();

	private final OutputStream out;
	private final int serial;
	private int sequencia;
	private long granule;
	private final List<byte[]> pendentes = new ArrayList<>();
	private int segmentosPendentes;
	private boolean fechado;

	OggOpusWriter(OutputStream out, int canais, int serial) throws IOException {
		this.out = out;
		this.serial = serial;
		escrever(List.of(opusHead(canais)), 0x02, 0); // BOS
		escrever(List.of(opusTags()), 0x00, 0);
	}

	/** Acrescenta um pacote que decodifica {@code amostras} amostras a 48 kHz. */
	void adicionar(byte[] pacote, int amostras) throws IOException {
		int seg = segmentos(pacote.length);
		if (!pendentes.isEmpty() && (pendentes.size() >= MAX_PACOTES_POR_PAGINA || segmentosPendentes + seg > MAX_SEGMENTOS)) {
			descarregar(0x00);
		}
		pendentes.add(pacote);
		segmentosPendentes += seg;
		granule += amostras;
	}

	/** Amostras (48 kHz) já gravadas, sem o pré-atraso. */
	long amostrasGravadas() {
		return granule;
	}

	@Override
	public void close() throws IOException {
		if (fechado) {
			return;
		}
		fechado = true;
		descarregar(0x04); // EOS (mesmo vazio, para o arquivo terminar corretamente)
		out.flush();
		out.close();
	}

	private void descarregar(int tipo) throws IOException {
		escrever(new ArrayList<>(pendentes), tipo, PRE_SKIP + granule);
		pendentes.clear();
		segmentosPendentes = 0;
	}

	private void escrever(List<byte[]> pacotes, int tipo, long granulePos) throws IOException {
		ByteArrayOutputStream tabela = new ByteArrayOutputStream();
		ByteArrayOutputStream corpo = new ByteArrayOutputStream();
		for (byte[] p : pacotes) {
			int n = p.length;
			while (n >= 255) {
				tabela.write(255);
				n -= 255;
			}
			tabela.write(n); // o último segmento (< 255) encerra o pacote, mesmo que seja 0
			corpo.write(p);
		}
		byte[] seg = tabela.toByteArray();
		ByteBuffer pagina = ByteBuffer.allocate(27 + seg.length + corpo.size()).order(ByteOrder.LITTLE_ENDIAN);
		pagina.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) tipo);
		pagina.putLong(granulePos).putInt(serial).putInt(sequencia++).putInt(0).put((byte) seg.length);
		pagina.put(seg).put(corpo.toByteArray());
		byte[] bytes = pagina.array();
		int crc = crc(bytes);
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
		out.write(bytes);
		out.flush();
	}

	private static int segmentos(int tamanho) {
		return tamanho / 255 + 1;
	}

	private static byte[] opusHead(int canais) {
		ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
		b.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) canais);
		b.putShort((short) PRE_SKIP).putInt(48_000).putShort((short) 0).put((byte) 0);
		return b.array();
	}

	private static byte[] opusTags() {
		byte[] fornecedor = "MediLink".getBytes(StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(8 + 4 + fornecedor.length + 4).order(ByteOrder.LITTLE_ENDIAN);
		b.put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(fornecedor.length).put(fornecedor).putInt(0);
		return b.array();
	}

	/** CRC-32 do Ogg: polinômio 0x04C11DB7, valor inicial 0, sem reflexão. */
	static int crc(byte[] dados) {
		int c = 0;
		for (byte d : dados) {
			c = (c << 8) ^ CRC[((c >>> 24) & 0xFF) ^ (d & 0xFF)];
		}
		return c;
	}

	private static int[] tabelaCrc() {
		int[] t = new int[256];
		for (int i = 0; i < 256; i++) {
			int r = i << 24;
			for (int k = 0; k < 8; k++) {
				r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
			}
			t[i] = r;
		}
		return t;
	}
}
