package br.com.medilink.sink;

/** Leitura mínima do cabeçalho (TOC) de um pacote Opus, RFC 6716 seção 3.1: só o necessário para contar amostras. */
final class OpusPacket {

	/** Amostras a 48 kHz do maior pacote válido (120 ms). */
	static final int MAX_AMOSTRAS = 5760;

	private OpusPacket() {
	}

	/** Total de amostras a 48 kHz que o pacote decodifica (todos os quadros dele). */
	static int amostras(byte[] pacote) {
		if (pacote == null || pacote.length < 1) {
			throw new IllegalArgumentException("Pacote Opus vazio");
		}
		int toc = pacote[0] & 0xFF;
		int quadros = switch (toc & 0x03) {
			case 0 -> 1;
			case 1, 2 -> 2;
			default -> {
				if (pacote.length < 2) {
					throw new IllegalArgumentException("Pacote Opus com código 3 sem contagem de quadros");
				}
				yield pacote[1] & 0x3F;
			}
		};
		int total = quadros * amostrasPorQuadro(toc >> 3);
		if (quadros < 1 || total > MAX_AMOSTRAS) {
			throw new IllegalArgumentException("Pacote Opus inválido (" + quadros + " quadros, " + total + " amostras)");
		}
		return total;
	}

	/** Amostras a 48 kHz de um quadro, dada a "config" (5 bits altos do TOC). */
	private static int amostrasPorQuadro(int config) {
		if (config < 12) { // SILK: 10, 20, 40, 60 ms
			return new int[] { 480, 960, 1920, 2880 }[config & 3];
		}
		if (config < 16) { // híbrido: 10 e 20 ms
			return (config & 1) == 0 ? 480 : 960;
		}
		return new int[] { 120, 240, 480, 960 }[config & 3]; // CELT: 2,5; 5; 10; 20 ms
	}

	/**
	 * Pacote de "silêncio" de um quadro (TOC com código 0 + 0xFF 0xFE, a forma que o libopus usa no DTX), com o
	 * mesmo modo/duração/canais do pacote de referência. Usado para preencher lacunas de tempo.
	 */
	static byte[] silencioComo(byte[] referencia) {
		int toc = (referencia[0] & 0xFC); // mantém config e flag estéreo; força código 0 (um quadro)
		return new byte[] { (byte) toc, (byte) 0xFF, (byte) 0xFE };
	}
}
