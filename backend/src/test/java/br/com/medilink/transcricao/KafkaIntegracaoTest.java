package br.com.medilink.transcricao;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Publicação de verdade no Redpanda local (infra/dev/redpanda.sh up). Sem o broker o teste é PULADO e aparece como
 * tal no relatório; a suíte padrão não depende dele.
 */
@SpringBootTest
class KafkaIntegracaoTest {

	@Autowired PublicadorDeJobs publicador;
	@Autowired TranscricaoProperties props;
	@Value("${spring.kafka.bootstrap-servers}") String broker;

	@BeforeEach
	void brokerNoAr() {
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
	}

	@Test
	void mensagemPublicadaChegaAoTopicoComAChaveDaConsulta() throws Exception {
		String chave = UUID.randomUUID().toString();
		String corpo = "{\"versao\":1,\"consultaId\":\"" + chave + "\",\"fontes\":[]}";

		publicador.publicar(chave, corpo);

		Map<String, Object> cfg = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker,
				ConsumerConfig.GROUP_ID_CONFIG, "teste-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (KafkaConsumer<String, String> consumidor = new KafkaConsumer<>(cfg)) {
			consumidor.subscribe(List.of(props.topicoJobs()));
			ConsumerRecord<String, String> achado = null;
			long limite = System.currentTimeMillis() + 15_000;
			while (achado == null && System.currentTimeMillis() < limite) {
				for (ConsumerRecord<String, String> r : consumidor.poll(Duration.ofMillis(500))) {
					if (chave.equals(r.key())) {
						achado = r;
					}
				}
			}
			assertThat(achado).as("mensagem no tópico %s", props.topicoJobs()).isNotNull();
			assertThat(achado.value()).isEqualTo(corpo);
		}
	}
}
