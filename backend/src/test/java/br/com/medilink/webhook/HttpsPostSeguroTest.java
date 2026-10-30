package br.com.medilink.webhook;

import br.com.medilink.webhook.HttpsPostSeguro.FalhaDeEnvio;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19: o cliente do webhook com TLS de verdade (servidor HTTPS local com certificado autoassinado gerado pelo keytool do
 * JDK). Prova a verificação do certificado e do nome, a ausência de redirecionamento e os bloqueios de destino.
 */
class HttpsPostSeguroTest {

	@TempDir static Path pasta;
	static SSLContext confiaNoCertificado;
	static HttpsServer servidor;
	static int porta;
	static final AtomicInteger resposta = new AtomicInteger(200);
	static final AtomicInteger atrasoMs = new AtomicInteger(0);
	static final List<String> pedidos = new CopyOnWriteArrayList<>();
	static final List<String> cabecalhosRecebidos = new CopyOnWriteArrayList<>();
	static final AtomicInteger requisicoes = new AtomicInteger();

	@BeforeAll
	static void subir() throws Exception {
		Path ks = pasta.resolve("servidor.p12");
		Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "s",
				"-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", "san=dns:localhost", "-validity", "2",
				"-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit").redirectErrorStream(true).start();
		assertThat(p.waitFor()).isZero();
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(ks)) {
			store.load(in, "changeit".toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(store, "changeit".toCharArray());
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(store); // confia no próprio certificado autoassinado do servidor de teste
		SSLContext servidorCtx = SSLContext.getInstance("TLS");
		servidorCtx.init(kmf.getKeyManagers(), null, null);
		confiaNoCertificado = SSLContext.getInstance("TLS");
		confiaNoCertificado.init(null, tmf.getTrustManagers(), null);

		servidor = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		servidor.setHttpsConfigurator(new HttpsConfigurator(servidorCtx));
		servidor.createContext("/", troca -> {
			requisicoes.incrementAndGet();
			pedidos.add(troca.getRequestMethod() + " " + troca.getRequestURI() + " " + new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			cabecalhosRecebidos.add(String.valueOf(troca.getRequestHeaders().getFirst("X-MediLink-Event")));
			try {
				Thread.sleep(atrasoMs.get());
			}
			catch (InterruptedException ignorado) {
				Thread.currentThread().interrupt();
			}
			if (resposta.get() == 302) {
				troca.getResponseHeaders().add("Location", "https://localhost:" + porta + "/outro");
			}
			troca.sendResponseHeaders(resposta.get(), -1);
			troca.close();
		});
		servidor.start();
		porta = servidor.getAddress().getPort();
	}

	@AfterAll
	static void parar() {
		servidor.stop(0);
	}

	@BeforeEach
	void zerar() {
		resposta.set(200);
		atrasoMs.set(0);
		pedidos.clear();
		cabecalhosRecebidos.clear();
		requisicoes.set(0);
	}

	private HttpsPostSeguro dev(SSLContext ctx) {
		return new HttpsPostSeguro(ctx, true, Duration.ofSeconds(3), Duration.ofSeconds(2));
	}

	private static byte[] corpo() {
		return "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void postPorTlsComCertificadoEhostVerificadosEntregaCorpoCaminhoECabecalhos() throws Exception {
		var r = dev(confiaNoCertificado).postar(URI.create("https://localhost:" + porta + "/hooks/x?tenant=7"), corpo(), Map.of("X-MediLink-Event", "transcricao.concluida"));

		assertThat(r.status()).isEqualTo(200);
		assertThat(pedidos).containsExactly("POST /hooks/x?tenant=7 {\"ok\":true}");
		assertThat(cabecalhosRecebidos).containsExactly("transcricao.concluida");
	}

	@Test
	void certificadoQueNaoBateComOHostEhRecusado() {
		// O certificado vale para "localhost", não para o IP: a verificação de nome precisa falhar.
		assertThatThrownBy(() -> dev(confiaNoCertificado).postar(URI.create("https://127.0.0.1:" + porta + "/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> assertThat(f.tipo()).isEqualTo("TLS_RECUSADO"));
		assertThat(requisicoes.get()).isZero(); // nada foi enviado
	}

	@Test
	void certificadoAutoassinadoSemConfiancaEhRecusado() throws Exception {
		assertThatThrownBy(() -> dev(SSLContext.getDefault()).postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> assertThat(f.tipo()).isEqualTo("TLS_RECUSADO"));
		assertThat(requisicoes.get()).isZero();
	}

	@Test
	void redirecionamentoNaoEhSeguido() throws Exception {
		resposta.set(302);

		var r = dev(confiaNoCertificado).postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of());

		assertThat(r.status()).isEqualTo(302);
		assertThat(requisicoes.get()).isEqualTo(1); // o /outro nunca é pedido
	}

	@Test
	void devolveOStatusDeErroDoDestinoSemInterpretarOCorpo() throws Exception {
		resposta.set(503);
		assertThat(dev(confiaNoCertificado).postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of()).status()).isEqualTo(503);
	}

	@Test
	void foraDoModoDevLoopbackEhBloqueadoAntesDeConectar() throws Exception {
		var producao = new HttpsPostSeguro(confiaNoCertificado, false, Duration.ofSeconds(3), Duration.ofSeconds(2));

		assertThatThrownBy(() -> producao.postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> {
					assertThat(f.tipo()).isEqualTo("DESTINO_BLOQUEADO");
					assertThat(f.permanente()).isTrue();
				});
		assertThatThrownBy(() -> producao.postar(URI.create("https://127.0.0.1:" + porta + "/x"), corpo(), Map.of())).isInstanceOf(FalhaDeEnvio.class);
		assertThatThrownBy(() -> producao.postar(URI.create("https://169.254.169.254/latest/meta-data"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> assertThat(f.tipo()).isEqualTo("DESTINO_BLOQUEADO"));
		assertThatThrownBy(() -> producao.postar(URI.create("https://[::1]:" + porta + "/x"), corpo(), Map.of())).isInstanceOf(FalhaDeEnvio.class);
		assertThat(requisicoes.get()).isZero();
	}

	@Test
	void foraDoModoDevHttpPuroEhProibido() {
		var producao = new HttpsPostSeguro(confiaNoCertificado, false, Duration.ofSeconds(3), Duration.ofSeconds(2));

		assertThatThrownBy(() -> producao.postar(URI.create("http://exemplo.test/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> {
					assertThat(f.tipo()).isEqualTo("ESQUEMA_PROIBIDO");
					assertThat(f.permanente()).isTrue();
				});
	}

	@Test
	void destinoLentoDaTempoEsgotadoEPodeSerRepetido() {
		atrasoMs.set(1500);
		var curto = new HttpsPostSeguro(confiaNoCertificado, true, Duration.ofSeconds(3), Duration.ofMillis(300));

		assertThatThrownBy(() -> curto.postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> {
					assertThat(f.tipo()).isEqualTo("TEMPO_ESGOTADO");
					assertThat(f.permanente()).isFalse();
				});
	}

	@Test
	void cabecalhoComQuebraDeLinhaNaoSai() {
		assertThatThrownBy(() -> dev(confiaNoCertificado).postar(URI.create("https://localhost:" + porta + "/x"), corpo(), Map.of("X-A", "b\r\nHost: evil")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(requisicoes.get()).isZero();
	}

	@Test
	void hostInexistenteEFalhaTemporariaDeDns() {
		assertThatThrownBy(() -> dev(confiaNoCertificado).postar(URI.create("https://host-que-nao-existe.invalid/x"), corpo(), Map.of()))
				.isInstanceOfSatisfying(FalhaDeEnvio.class, f -> assertThat(f.tipo()).isEqualTo("DNS_FALHOU"));
	}
}
