package br.com.medilink.transcricao;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** T14: os tópicos da fila, que guardam o texto das consultas, têm retenção curta no broker de verdade (Redpanda local). */
@SpringBootTest
@TestPropertySource(properties = {"spring.kafka.admin.auto-create=true", "medilink.transcricao.retencao-dos-topicos=6h"})
class TopicosDaFilaTest {

	@Autowired TranscricaoProperties props;
	@Value("${spring.kafka.bootstrap-servers}") String broker;

	@Test
	void osTopicosNascemComRetencaoCurtaEOPrazoEReajustadoNosExistentes() throws Exception {
		String[] hp = broker.split(":");
		boolean ok;
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 1500);
			ok = true;
		}
		catch (Exception e) {
			ok = false;
		}
		assumeTrue(ok, "Redpanda indisponível em " + broker + " (rode infra/dev/redpanda.sh up)");

		try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", broker))) {
			for (String topico : List.of(props.topicoJobs(), props.topicoResultados())) {
				var config = admin.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC, topico)))
						.all().get().values().iterator().next();
				assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value()).as(topico).isEqualTo("21600000"); // 6 h
			}
		}
	}
}
