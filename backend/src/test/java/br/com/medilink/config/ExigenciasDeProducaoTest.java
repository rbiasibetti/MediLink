package br.com.medilink.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** T14: produção não sobe com tráfego interno sem TLS verificado. */
class ExigenciasDeProducaoTest {

	private static MockEnvironment seguro() {
		return new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:sqlserver://db:1433;databaseName=medilink;encrypt=true;trustServerCertificate=false")
				.withProperty("server.ssl.enabled", "true")
				.withProperty("spring.kafka.security.protocol", "SSL");
	}

	@Test
	void configuracaoSeguraPassa() {
		assertThat(ExigenciasDeProducao.violacoes(seguro())).isEmpty();
		assertThat(ExigenciasDeProducao.violacoes(seguro().withProperty("spring.kafka.security.protocol", "sasl_ssl"))).isEmpty();
	}

	@Test
	void ambienteDeDesenvolvimentoEBarradoEmProducao() {
		var dev = new MockEnvironment()
				.withProperty("spring.datasource.url", "jdbc:sqlserver://localhost:1433;databaseName=medilink_dev;encrypt=true;trustServerCertificate=true")
				.withProperty("medilink.crypto.aceitar-texto-legado", "true");

		assertThat(ExigenciasDeProducao.violacoes(dev)).hasSize(4)
				.anyMatch(m -> m.contains("trustServerCertificate"))
				.anyMatch(m -> m.contains("server.ssl.enabled"))
				.anyMatch(m -> m.contains("kafka"))
				.anyMatch(m -> m.contains("aceitar-texto-legado"));
	}

	@Test
	void bancoSemTlsEBarradoEAMensagemNaoRepeteAUrl() {
		var env = seguro().withProperty("spring.datasource.url", "jdbc:sqlserver://db:1433;user=sa;password=SEGREDO;databaseName=m");

		var problemas = ExigenciasDeProducao.violacoes(env);

		assertThat(problemas).hasSize(1).first().asString().contains("encrypt=true").doesNotContain("SEGREDO");
	}

	@Test
	void trustServerCertificateEmMaiusculasOuComEspacoTambemEBarrado() {
		var env = seguro().withProperty("spring.datasource.url", "jdbc:sqlserver://db;encrypt=true; TrustServerCertificate = TRUE");

		assertThat(ExigenciasDeProducao.violacoes(env)).hasSize(1);
	}
}
