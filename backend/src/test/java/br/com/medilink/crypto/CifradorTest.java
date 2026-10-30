package br.com.medilink.crypto;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T14: a cifra em repouso do texto (colunas) e do áudio (arquivos). Sem Spring: só a classe. */
class CifradorTest {

	private static String chave(int semente) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) semente);
		return Base64.getEncoder().encodeToString(k);
	}

	private static Cifrador cifrador(String chave, String id, Map<String, String> antigas, boolean legado) {
		return new Cifrador(new CryptoProperties(chave, id, antigas, legado));
	}

	private final Cifrador c = cifrador(chave(1), "k1", Map.of(), false);

	private static byte[] aleatorios(int n) {
		byte[] b = new byte[n];
		new SecureRandom().nextBytes(b);
		return b;
	}

	private byte[] cifrarAudio(Cifrador cifrador, byte[] claro) throws IOException {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		try (OutputStream out = cifrador.cifrarAudio(saida)) {
			out.write(claro, 0, claro.length / 2);       // em pedaços, como o writer do Ogg faz
			out.flush();
			out.write(claro, claro.length / 2, claro.length - claro.length / 2);
		}
		return saida.toByteArray();
	}

	// ---- texto ----

	@Test
	void textoVoltaIgualEOArmazenadoNaoMostraNada() {
		String claro = "Médico: A senhora sente dor de cabeça há quantos dias?";

		String guardado = c.cifrarTexto(claro);

		assertThat(guardado).startsWith("enc:k1:").doesNotContain("dor").doesNotContain("Médico");
		assertThat(c.decifrarTexto(guardado)).isEqualTo(claro);
		assertThat(c.cifrarTexto(claro)).isNotEqualTo(guardado); // nonce novo a cada vez
		assertThat(c.decifrarTexto(c.cifrarTexto(""))).isEmpty();
	}

	@Test
	void textoAdulteradoOuComOutraChaveNaoDecifra() {
		String guardado = c.cifrarTexto("segredo");
		char[] letras = guardado.toCharArray();
		letras[letras.length - 3] = letras[letras.length - 3] == 'A' ? 'B' : 'A';

		assertThatThrownBy(() -> c.decifrarTexto(new String(letras))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifrador(chave(2), "k1", Map.of(), false).decifrarTexto(guardado)).isInstanceOf(IllegalStateException.class);
		// O id da chave é dado autenticado: trocar o rótulo para outra chave conhecida não engana.
		var duas = cifrador(chave(1), "k1", Map.of("k2", chave(2)), false);
		assertThatThrownBy(() -> duas.decifrarTexto(guardado.replace("enc:k1:", "enc:k2:"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void textoSemPrefixoSoEAceitoNoModoLegado() {
		assertThatThrownBy(() -> c.decifrarTexto("texto puro de antes da T14")).isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("texto puro");
		assertThat(cifrador(chave(1), "k1", Map.of(), true).decifrarTexto("texto puro de antes da T14")).isEqualTo("texto puro de antes da T14");
	}

	@Test
	void rotacaoDeChaveMantemODadoAntigoLegivel() {
		String antigo = c.cifrarTexto("gravado com a k1");
		var depois = cifrador(chave(2), "k2", Map.of("k1", chave(1)), false);

		assertThat(depois.decifrarTexto(antigo)).isEqualTo("gravado com a k1");
		assertThat(depois.cifrarTexto("novo")).startsWith("enc:k2:");
		assertThat(depois.cifradoComChaveAtiva(antigo)).isFalse();
		assertThat(depois.cifradoComChaveAtiva(depois.cifrarTexto("x"))).isTrue();
		assertThatThrownBy(() -> cifrador(chave(2), "k2", Map.of(), false).decifrarTexto(antigo)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void chaveInvalidaImpedeASubida() {
		assertThatThrownBy(() -> cifrador("nao é base64!!", "k1", Map.of(), false)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> cifrador(Base64.getEncoder().encodeToString(new byte[16]), "k1", Map.of(), false))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> cifrador(chave(1), "k:1", Map.of(), false)).isInstanceOf(IllegalStateException.class);
	}

	// ---- áudio ----

	@Test
	void audioVoltaIgualEOArquivoNaoTemNadaLegivel() throws Exception {
		byte[] claro = aleatorios(50_000);
		System.arraycopy("OggSOpusHead".getBytes(StandardCharsets.US_ASCII), 0, claro, 0, 12);

		byte[] cifrado = cifrarAudio(c, claro);

		assertThat(new String(cifrado, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("MLA1");
		assertThat(new String(cifrado, StandardCharsets.ISO_8859_1)).doesNotContain("OggS").doesNotContain("OpusHead");
		assertThat(c.decifrarAudio(new ByteArrayInputStream(cifrado))).isEqualTo(claro);
	}

	@Test
	void audioVazioEBlocoExatoFuncionam() throws Exception {
		for (int n : new int[] {0, 1, Cifrador.BLOCO_AUDIO - 1, Cifrador.BLOCO_AUDIO, Cifrador.BLOCO_AUDIO + 1, 3 * Cifrador.BLOCO_AUDIO}) {
			byte[] claro = aleatorios(n);
			assertThat(c.decifrarAudio(new ByteArrayInputStream(cifrarAudio(c, claro)))).as("tamanho %d", n).isEqualTo(claro);
		}
	}

	@Test
	void cadaArquivoTemSuaPropriaChave() throws Exception {
		byte[] claro = aleatorios(1000);

		byte[] a = cifrarAudio(c, claro);
		byte[] b = cifrarAudio(c, claro);

		assertThat(a).isNotEqualTo(b); // sal diferente: nunca repete (chave, nonce)
	}

	@Test
	void audioAdulteradoTruncadoOuReordenadoNaoDecifra() throws Exception {
		byte[] cifrado = cifrarAudio(c, aleatorios(3 * Cifrador.BLOCO_AUDIO + 100));

		byte[] mexido = cifrado.clone();
		mexido[mexido.length - 40] ^= 1;
		assertThatThrownBy(() -> c.decifrarAudio(new ByteArrayInputStream(mexido))).isInstanceOf(IOException.class);

		byte[] semMagia = cifrado.clone();
		semMagia[0] = 'X';
		assertThatThrownBy(() -> c.decifrarAudio(new ByteArrayInputStream(semMagia))).isInstanceOf(IOException.class);

		// Trocar dois blocos de lugar: o nonce é o índice, então não autentica.
		int cab = 4 + 1 + 2 + 16 + 4;
		int bloco = 4 + Cifrador.BLOCO_AUDIO + 16;
		byte[] trocado = cifrado.clone();
		System.arraycopy(cifrado, cab + bloco, trocado, cab, bloco);
		System.arraycopy(cifrado, cab, trocado, cab + bloco, bloco);
		assertThatThrownBy(() -> c.decifrarAudio(new ByteArrayInputStream(trocado))).isInstanceOf(IOException.class);

		// Cabeçalho de outro arquivo (outro sal) colado nos blocos: também falha.
		byte[] outro = cifrarAudio(c, aleatorios(3 * Cifrador.BLOCO_AUDIO + 100));
		byte[] colado = cifrado.clone();
		System.arraycopy(outro, 0, colado, 0, cab);
		assertThatThrownBy(() -> c.decifrarAudio(new ByteArrayInputStream(colado))).isInstanceOf(IOException.class);

		// Arquivo cortado no meio de um bloco não passa em silêncio.
		byte[] cortado = Arrays.copyOf(cifrado, cifrado.length - 10);
		assertThatThrownBy(() -> c.decifrarAudio(new ByteArrayInputStream(cortado))).isInstanceOf(IOException.class);
	}

	@Test
	void audioComChaveErradaOuRotacionadaSeguePelaChaveDoCabecalho() throws Exception {
		byte[] claro = aleatorios(5000);
		byte[] cifrado = cifrarAudio(c, claro);

		assertThatThrownBy(() -> cifrador(chave(9), "k1", Map.of(), false).decifrarAudio(new ByteArrayInputStream(cifrado)))
				.isInstanceOf(IOException.class);
		var depois = cifrador(chave(2), "k2", Map.of("k1", chave(1)), false);
		assertThat(depois.decifrarAudio(new ByteArrayInputStream(cifrado))).isEqualTo(claro); // o id da chave está no cabeçalho
	}

	@Test
	void lePeloMesmoFormatoOArquivoGeradoPeloWorkerEmPythonEORegressaoDoFormato() throws Exception {
		// Chave 0x01 x32 e conteúdo (i*7 % 251): os mesmos nos testes do worker (worker/tests/test_cifra.py).
		byte[] esperado = new byte[17000];
		for (int i = 0; i < esperado.length; i++) {
			esperado[i] = (byte) ((i * 7) % 251);
		}
		var comChaveFixa = cifrador(chave(1), "k1", Map.of(), false);
		for (String arquivo : new String[] {"audio-python.mla", "audio-java.mla"}) {
			try (var in = getClass().getResourceAsStream("/cifra/" + arquivo)) {
				assertThat(comChaveFixa.decifrarAudio(in)).as(arquivo).isEqualTo(esperado);
			}
		}
	}
}
