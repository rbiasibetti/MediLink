package br.com.medilink.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T19: o cliente do agendamento só controla o que vem DEPOIS da base cadastrada, e mesmo isso é validado com rigor. */
class WebhookDestinoTest {

	private static final URI BASE = URI.create("https://api.cliente.com.br/hooks/medilink");

	@Test
	void aceitaCaminhoEParametrosNormais() {
		for (String ok : new String[] {"/clinica-42/consultas", "/a/b/c?tenant=7&x=1", "/", "/clinica%20sul/%C3%A9?q=a%40b.com", "/v1/ret?token=abc.DEF_123-~"}) {
			assertThat(WebhookDestino.validarCaminho(ok)).isEqualTo(ok);
		}
		assertThat(WebhookDestino.validarCaminho(null)).isNull();
		assertThat(WebhookDestino.validarCaminho("   ")).isNull();
	}

	@Test
	void recusaTudoQuePossaMudarOHostOuSairDoPrefixo() {
		String[] ruins = {
				"clinica",                       // sem "/" inicial
				"//evil.com/x",                  // host por URL relativa a protocolo
				"/\\evil.com",                   // barra invertida
				"/a/../../etc/passwd",           // saída do prefixo
				"/a/./b",                        // segmento "."
				"/a/%2e%2e/b",                   // ".." codificado
				"/a/%2E%2E/b",
				"/a//b",                         // segmento vazio
				"/a/b%2Fc",                      // "/" codificado dentro de um segmento
				"/x@evil.com",                   // @ cru (userinfo)
				"/x#frag",                       // fragmento
				"/x y",                          // espaço
				"/x\r\nHost: evil.com",          // injeção de cabeçalho
				"/x%0d%0aHost:evil",             // CRLF codificado
				"/x%00",                         // NUL
				"/x%zz",                         // % inválido
				"/x?a=1?b=2",                    // "?" repetido
				"/x?" + "a=1&".repeat(25),       // parâmetros demais
				"/" + "a".repeat(600),           // grande demais
				"http://evil.com/x",             // URL completa
				"/x<script>",
		};
		for (String ruim : ruins) {
			assertThatThrownBy(() -> WebhookDestino.validarCaminho(ruim)).as(ruim).isInstanceOf(ResponseStatusException.class);
		}
	}

	@Test
	void comporMantemEsquemaHostPortaEPrefixoDaBase() {
		URI u = WebhookDestino.compor(BASE, "/clinica-42/consultas?tenant=7");

		assertThat(u.toString()).isEqualTo("https://api.cliente.com.br/hooks/medilink/clinica-42/consultas?tenant=7");
		assertThat(WebhookDestino.compor(URI.create("https://h.test:8443/"), "/a").toString()).isEqualTo("https://h.test:8443/a");
		assertThat(WebhookDestino.compor(BASE, null).toString()).isEqualTo("https://api.cliente.com.br/hooks/medilink");
	}

	@Test
	void baseValidaSoAceitaHttpsSemUsuarioParametroOuFragmento() {
		assertThat(WebhookDestino.validarBase("https://api.cliente.com.br/hooks", false)).isNotNull();
		for (String ruim : new String[] {"http://api.cliente.com.br/h", "https://u:p@api.cliente.com.br/h", "https://api.cliente.com.br/h?x=1",
				"https://api.cliente.com.br/h#x", "https://api.cliente.com.br/../h", "ftp://x.test/h", "https:/sem-host", "", "x".repeat(600)}) {
			assertThatThrownBy(() -> WebhookDestino.validarBase(ruim, false)).as(ruim).isInstanceOf(ResponseStatusException.class);
		}
		assertThat(WebhookDestino.validarBase("http://localhost:9000/h", true)).isNotNull(); // só no modo dev
	}

	@Test
	void soEnderecosDeInternetPublicaSaoDestino() throws Exception {
		for (String publico : new String[] {"8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111"}) {
			assertThat(EnderecoPublico.permitido(InetAddress.getByName(publico))).as(publico).isTrue();
		}
		for (String proibido : new String[] {"127.0.0.1", "127.5.5.5", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1",
				"169.254.169.254", "100.64.0.1", "100.127.255.255", "224.0.0.1", "240.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.2.1",
				"198.51.100.1", "203.0.113.9", "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "2001:db8::1", "::ffff:127.0.0.1",
				"::ffff:10.0.0.1", "::ffff:169.254.169.254"}) {
			assertThat(EnderecoPublico.permitido(InetAddress.getByName(proibido))).as(proibido).isFalse();
		}
		// Bordas dos blocos privados: 172.15 e 172.32 são públicos; 100.63 e 100.128 também.
		assertThat(EnderecoPublico.permitido(InetAddress.getByName("172.15.0.1"))).isTrue();
		assertThat(EnderecoPublico.permitido(InetAddress.getByName("172.32.0.1"))).isTrue();
		assertThat(EnderecoPublico.permitido(InetAddress.getByName("100.63.0.1"))).isTrue();
		assertThat(EnderecoPublico.permitido(InetAddress.getByName("100.128.0.1"))).isTrue();
	}

	@Test
	void assinaturaConfereOCorpoOSegredoEAIdade() {
		byte[] corpo = "{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		String cab = Assinatura.cabecalho("whsec_segredo", 1_700_000_000L, corpo);

		assertThat(cab).matches("t=1700000000,v1=[0-9a-f]{64}");
		assertThat(Assinatura.valida("whsec_segredo", cab, corpo, 1_700_000_100L, 300)).isTrue();
		assertThat(Assinatura.valida("outro-segredo", cab, corpo, 1_700_000_100L, 300)).isFalse();
		assertThat(Assinatura.valida("whsec_segredo", cab, "{\"a\":2}".getBytes(), 1_700_000_100L, 300)).isFalse();
		assertThat(Assinatura.valida("whsec_segredo", cab, corpo, 1_700_001_000L, 300)).isFalse(); // velha demais (replay)
		assertThat(Assinatura.valida("whsec_segredo", "lixo", corpo, 1_700_000_100L, 300)).isFalse();
	}
}
