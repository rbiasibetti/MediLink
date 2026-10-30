package br.com.medilink.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * AES-256-GCM para os dados sensíveis em repouso: o texto da transcrição (colunas do banco) e os arquivos de áudio
 * transitórios. Duas chaves de dados saem da chave mestra por HKDF-SHA256 ({@code texto-v1} e, por arquivo, {@code audio-v1}
 * com um sal aleatório), então o vazamento de uma não entrega a outra e nenhum (chave, nonce) se repete.
 * <p>
 * Texto: {@code enc:<idDaChave>:<base64(nonce12 | cifrado+tag)>}, com o id da chave como dado autenticado.
 * <p>
 * Áudio ({@code MLA1}): cabeçalho {@code "MLA1" | idLen(1) | id | sal(16) | tamanhoDoBloco(4)}, seguido de blocos
 * {@code compr(4) | cifrado+tag}. O nonce de cada bloco é o seu índice (único, pois a chave é única por arquivo) e o
 * cabeçalho inteiro é dado autenticado, então bloco trocado de lugar ou de arquivo não decifra. O formato é lido
 * igualmente pelo worker (Python); mudar um lado exige mudar o outro.
 */
@Component
public class Cifrador {

	public static final byte[] MAGIA_AUDIO = {'M', 'L', 'A', '1'};
	public static final String PREFIXO_TEXTO = "enc:";
	static final int BLOCO_AUDIO = 16 * 1024;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final String idAtivo;
	private final Map<String, byte[]> chaves = new HashMap<>();
	private final boolean aceitarTextoLegado;

	public Cifrador(CryptoProperties props) {
		this.idAtivo = props.chaveId();
		this.aceitarTextoLegado = props.aceitarTextoLegado();
		chaves.put(idAtivo, mestra(props.chave(), "chave"));
		props.chavesAnteriores().forEach((id, valor) -> chaves.putIfAbsent(id, mestra(valor, "chave anterior " + id)));
		if (idAtivo.isBlank() || idAtivo.contains(":") || idAtivo.length() > 32) {
			throw new IllegalStateException("medilink.crypto.chave-id inválido");
		}
	}

	private static byte[] mestra(String base64, String nome) {
		byte[] k;
		try {
			k = Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("medilink.crypto: " + nome + " não é Base64 válido");
		}
		if (k.length != 32) {
			throw new IllegalStateException("medilink.crypto: " + nome + " precisa ter 32 bytes (AES-256)");
		}
		return k;
	}

	// ---------------------------------------------------------------- texto (colunas do banco)

	public String cifrarTexto(String claro) {
		try {
			byte[] nonce = new byte[12];
			RANDOM.nextBytes(nonce);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derivar(chaves.get(idAtivo), new byte[32], "texto-v1"), "AES"),
					new GCMParameterSpec(TAG_BITS, nonce));
			c.updateAAD(idAtivo.getBytes(StandardCharsets.UTF_8));
			byte[] cifrado = c.doFinal(claro.getBytes(StandardCharsets.UTF_8));
			return PREFIXO_TEXTO + idAtivo + ":" + Base64.getEncoder().encodeToString(
					ByteBuffer.allocate(nonce.length + cifrado.length).put(nonce).put(cifrado).array());
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("falha ao cifrar", e);
		}
	}

	public String decifrarTexto(String armazenado) {
		if (!armazenado.startsWith(PREFIXO_TEXTO)) {
			if (aceitarTextoLegado) {
				return armazenado;
			}
			throw new IllegalStateException("dado em repouso sem cifra (esperava o prefixo enc:)");
		}
		int dois = armazenado.indexOf(':', PREFIXO_TEXTO.length());
		if (dois < 0) {
			throw new IllegalStateException("dado cifrado malformado");
		}
		String id = armazenado.substring(PREFIXO_TEXTO.length(), dois);
		byte[] mestra = chaves.get(id);
		if (mestra == null) {
			throw new IllegalStateException("chave de cifra desconhecida: " + id);
		}
		try {
			byte[] bytes = Base64.getDecoder().decode(armazenado.substring(dois + 1));
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derivar(mestra, new byte[32], "texto-v1"), "AES"),
					new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(bytes, 0, 12)));
			c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
			return new String(c.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
			throw new IllegalStateException("dado cifrado inválido ou adulterado");
		}
	}

	/** O dado já está cifrado com a chave ativa (para saber o que precisa ser recifrado depois de uma rotação). */
	public boolean cifradoComChaveAtiva(String armazenado) {
		return armazenado.startsWith(PREFIXO_TEXTO + idAtivo + ":");
	}

	// ---------------------------------------------------------------- áudio (arquivos)

	/** Envolve a saída: tudo o que for escrito vai cifrado, em blocos. {@code close()} fecha também a saída original. */
	public OutputStream cifrarAudio(OutputStream destino) throws IOException {
		byte[] sal = new byte[16];
		RANDOM.nextBytes(sal);
		byte[] id = idAtivo.getBytes(StandardCharsets.UTF_8);
		byte[] cabecalho = ByteBuffer.allocate(4 + 1 + id.length + 16 + 4).put(MAGIA_AUDIO).put((byte) id.length).put(id)
				.put(sal).putInt(BLOCO_AUDIO).array();
		destino.write(cabecalho);
		return new FluxoCifrado(destino, new SecretKeySpec(derivar(chaves.get(idAtivo), sal, "audio-v1"), "AES"), cabecalho);
	}

	/** Lê um arquivo {@code MLA1} inteiro e devolve o conteúdo claro. Bloco adulterado ou chave errada lança {@link IOException}. */
	public byte[] decifrarAudio(InputStream origem) throws IOException {
		DataInputStream in = new DataInputStream(origem);
		byte[] magia = new byte[4];
		in.readFully(magia);
		if (!Arrays.equals(magia, MAGIA_AUDIO)) {
			throw new IOException("arquivo de áudio sem o cabeçalho de cifra");
		}
		int idLen = in.readUnsignedByte();
		byte[] id = new byte[idLen];
		in.readFully(id);
		byte[] sal = new byte[16];
		in.readFully(sal);
		int bloco = in.readInt();
		byte[] mestra = chaves.get(new String(id, StandardCharsets.UTF_8));
		if (mestra == null || bloco <= 0 || bloco > (1 << 24)) {
			throw new IOException("cabeçalho de áudio inválido ou chave desconhecida");
		}
		byte[] cabecalho = ByteBuffer.allocate(4 + 1 + idLen + 16 + 4).put(magia).put((byte) idLen).put(id).put(sal).putInt(bloco).array();
		SecretKeySpec chave = new SecretKeySpec(derivar(mestra, sal, "audio-v1"), "AES");
		ByteArrayOutputStream claro = new ByteArrayOutputStream();
		for (long indice = 0;; indice++) {
			int compr;
			try {
				compr = in.readInt();
			}
			catch (EOFException fim) {
				return claro.toByteArray();
			}
			if (compr < TAG_BITS / 8 || compr > bloco + TAG_BITS / 8) {
				throw new IOException("bloco de áudio com tamanho inválido");
			}
			byte[] cifrado = new byte[compr];
			in.readFully(cifrado);
			try {
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, nonce(indice)));
				c.updateAAD(cabecalho);
				claro.write(c.doFinal(cifrado));
			}
			catch (AEADBadTagException e) {
				throw new IOException("bloco de áudio adulterado ou chave errada");
			}
			catch (GeneralSecurityException e) {
				throw new IOException("falha ao decifrar o áudio");
			}
		}
	}

	private static byte[] nonce(long indice) {
		return ByteBuffer.allocate(12).putInt(0).putLong(indice).array();
	}

	private static final class FluxoCifrado extends OutputStream {

		private final OutputStream destino;
		private final SecretKeySpec chave;
		private final byte[] cabecalho;
		private final byte[] buffer = new byte[BLOCO_AUDIO];
		private int usados;
		private long indice;
		private boolean fechado;

		FluxoCifrado(OutputStream destino, SecretKeySpec chave, byte[] cabecalho) {
			this.destino = destino;
			this.chave = chave;
			this.cabecalho = cabecalho;
		}

		@Override
		public void write(int b) throws IOException {
			write(new byte[] {(byte) b}, 0, 1);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			while (len > 0) {
				int n = Math.min(len, BLOCO_AUDIO - usados);
				System.arraycopy(b, off, buffer, usados, n);
				usados += n;
				off += n;
				len -= n;
				if (usados == BLOCO_AUDIO) {
					emitir();
				}
			}
		}

		/** Não emite bloco parcial: um bloco por quantidade de dados evita 20 bytes de custo a cada página Ogg. */
		@Override
		public void flush() throws IOException {
			destino.flush();
		}

		private void emitir() throws IOException {
			if (usados == 0) {
				return;
			}
			try {
				Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
				c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, nonce(indice++)));
				c.updateAAD(cabecalho);
				byte[] cifrado = c.doFinal(buffer, 0, usados);
				destino.write(ByteBuffer.allocate(4).putInt(cifrado.length).array());
				destino.write(cifrado);
			}
			catch (GeneralSecurityException e) {
				throw new IOException("falha ao cifrar o áudio", e);
			}
			usados = 0;
		}

		@Override
		public void close() throws IOException {
			if (fechado) {
				return;
			}
			fechado = true;
			try {
				emitir();
				destino.flush();
			}
			finally {
				destino.close();
			}
		}
	}

	/** HKDF-SHA256 (RFC 5869) com uma única saída de 32 bytes. */
	static byte[] derivar(byte[] mestra, byte[] sal, String info) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(sal, "HmacSHA256"));
			byte[] prk = mac.doFinal(mestra);
			mac.init(new SecretKeySpec(prk, "HmacSHA256"));
			mac.update(info.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) 1);
			return mac.doFinal();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HKDF indisponível", e);
		}
	}
}
