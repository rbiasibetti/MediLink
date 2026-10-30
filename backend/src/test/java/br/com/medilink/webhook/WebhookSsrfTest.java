package br.com.medilink.webhook;

import br.com.medilink.domain.WebhookEntrega.Status;
import br.com.medilink.integracao.ApiKeyService;
import br.com.medilink.integracao.SistemaIntegrado;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.WebhookEntregaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T19 com a configuração de PRODUÇÃO (modo dev desligado): mesmo que alguém consiga cadastrar uma base que aponte para
 * dentro da rede, o servidor não conecta. Não há receptor nesta classe: se ele fosse contatado, o teste não teria onde
 * receber, e o estado "FALHA com código de bloqueio" prova que o envio foi barrado antes de abrir a conexão.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookSsrfTest {

	@Autowired MockMvc mvc;
	@Autowired IntegradorRepository integradores;
	@Autowired ApiKeyService keys;
	@Autowired WebhookEventos eventos;
	@Autowired WebhookDespachante despachante;
	@Autowired WebhookEntregaRepository entregas;
	@Autowired JdbcTemplate jdbc;

	private String entregaComBase(String base) throws Exception {
		var sistema = new SistemaIntegrado(integradores, keys);
		jdbc.update("UPDATE integrador SET webhook_base_url = ?, webhook_ativo = 1 WHERE id = ?", base, sistema.integrador.getId());
		// o segredo precisa existir; grava pela entidade (cifra)
		var i = integradores.findById(sistema.integrador.getId()).orElseThrow();
		i.configurarWebhook(base, "whsec_x", true);
		integradores.save(i);
		String json = """
				{"medico":{"nome":"Dr","chave":"M1"},"agendamento":{"chave":"s-%s","quando":{"dataHora":"%s"}},"paciente":{"nome":"J"},
				 "webhook":{"caminho":"/c"}}""".formatted(UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC));
		String r = mvc.perform(post("/api/integracao/v1/agendamentos").header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"chaveReuniao\":\"([^\"]+)\"").matcher(r);
		assertThat(m.find()).isTrue();
		UUID consultaId = jdbc.queryForObject("SELECT id FROM consulta WHERE agendamento_id = ?", UUID.class, UUID.fromString(m.group(1)));
		eventos.registrar(consultaId, WebhookEventos.INDISPONIVEL, "SEM_AUDIO");
		despachante.processarPendentes();
		var e = entregas.findByConsultaIdAndEvento(consultaId, WebhookEventos.INDISPONIVEL).orElseThrow();
		assertThat(e.getStatus()).isEqualTo(Status.FALHA);
		return e.getUltimoErro();
	}

	@Test
	void baseApontandoParaOLoopbackOuParaOsMetadadosDeNuvemNaoEhContactada() throws Exception {
		assertThat(entregaComBase("https://127.0.0.1:9/hooks")).isEqualTo("DESTINO_BLOQUEADO");
		assertThat(entregaComBase("https://localhost:9/hooks")).isEqualTo("DESTINO_BLOQUEADO");
		assertThat(entregaComBase("https://169.254.169.254/latest")).isEqualTo("DESTINO_BLOQUEADO");
		assertThat(entregaComBase("https://10.0.0.5/hooks")).isEqualTo("DESTINO_BLOQUEADO");
		assertThat(entregaComBase("https://[::1]/hooks")).isEqualTo("DESTINO_BLOQUEADO");
	}

	@Test
	void baseEmHttpPuroNaoEhAceitaMesmoSeAlguemGravarDiretoNoBanco() throws Exception {
		assertThat(entregaComBase("http://exemplo.cliente.com.br/hooks")).isEqualTo("DESTINO_INVALIDO");
	}
}
