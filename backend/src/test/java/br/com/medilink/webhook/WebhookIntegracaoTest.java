package br.com.medilink.webhook;

import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.WebhookEntrega;
import br.com.medilink.domain.WebhookEntrega.Status;
import br.com.medilink.integracao.ApiKeyService;
import br.com.medilink.integracao.SistemaIntegrado;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.WebhookEntregaRepository;
import br.com.medilink.retencao.RetencaoService;
import br.com.medilink.transcricao.PublicadorDeJobs;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService;
import br.com.medilink.transcricao.TranscricaoJobService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T19 de ponta a ponta contra um receptor HTTP local (modo dev): criação com destino, consulta até a transcrição, entrega
 * assinada, tentativas com recuo, falha definitiva e a regra de que transcrição não entregue não é apagada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"medilink.webhook.modo-dev=true", "medilink.retencao.transcricao=30d"})
class WebhookIntegracaoTest {

	private static final String CPF = "529.982.247-25";
	private static final String SEGREDO = "whsec_segredo-de-teste";

	record Recebido(String caminho, String corpo, String assinatura, String evento, String eventoId) {
	}

	@Autowired MockMvc mvc;
	@Autowired IntegradorRepository integradores;
	@Autowired ApiKeyService keys;
	@Autowired WebhookDespachante despachante;
	@Autowired WebhookEntregaRepository entregas;
	@Autowired TranscricaoJobService jobsService;
	@Autowired TranscricaoJobRepository jobs;
	@Autowired ResultadoDaTranscricaoService resultados;
	@Autowired AudioTransitorioRepository audios;
	@Autowired RetencaoService retencao;
	@Autowired JsonMapper json;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean PublicadorDeJobs publicador;

	private HttpServer receptor;
	private final List<Recebido> recebidos = new CopyOnWriteArrayList<>();
	private final AtomicInteger resposta = new AtomicInteger(200);
	private SistemaIntegrado sistema;

	@BeforeEach
	void montar() throws Exception {
		receptor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		receptor.createContext("/", t -> {
			recebidos.add(new Recebido(t.getRequestURI().toString(), new String(t.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
					t.getRequestHeaders().getFirst("X-MediLink-Signature"), t.getRequestHeaders().getFirst("X-MediLink-Event"),
					t.getRequestHeaders().getFirst("X-MediLink-Event-Id")));
			t.sendResponseHeaders(resposta.get(), -1);
			t.close();
		});
		receptor.start();
		sistema = new SistemaIntegrado(integradores, keys);
		sistema.integrador.configurarWebhook("http://localhost:" + receptor.getAddress().getPort() + "/hooks/medilink", SEGREDO, true);
		integradores.save(sistema.integrador);
	}

	@AfterEach
	void parar() {
		receptor.stop(0);
	}

	private String chave;
	private String sala;
	private UUID consultaId;

	private void criarReuniao(String extra) throws Exception {
		String corpo = """
				{"medico":{"nome":"Dra. Ana Lima","chave":"CRM-1"},"agendamento":{"chave":"w-%s","quando":{"dataHora":"%s"}},
				 "paciente":{"nome":"Maria Souza","cpf":"%s","email":"maria@mail.test"}%s}""".formatted(UUID.randomUUID(),
				Instant.now().plus(10, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC), CPF, extra);
		ResultActions r = mvc.perform(post("/api/integracao/v1/agendamentos").header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(corpo))
				.andExpect(status().isCreated());
		chave = campo(r, "chaveReuniao");
		sala = campo(r, "linkPaciente").replaceAll(".*/sala/", "");
		consultaId = jdbc.queryForObject("SELECT id FROM consulta WHERE agendamento_id = ?", UUID.class, UUID.fromString(chave));
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(r.andReturn().getResponse().getContentAsString());
		assertThat(m.find()).isTrue();
		return m.group(1);
	}

	/** Consulta de ponta a ponta até o encerramento, com o médico entrando pelo link e o paciente consentindo. */
	private void consultaEncerrada() throws Exception {
		String link = campo(mvc.perform(post("/api/integracao/v1/agendamentos/" + chave + "/acesso-medico").header("X-API-Key", sistema.chave())
				.contentType(MediaType.APPLICATION_JSON).content("{\"nomeMedico\":\"Dra. Ana Lima\",\"chaveMedico\":\"CRM-1\"}")).andExpect(status().isOk()), "linkMedico");
		String sessao = "Bearer " + campo(mvc.perform(post("/api/medico/sessao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + link.replaceAll(".*#t=", "") + "\"}")).andExpect(status().isOk()), "sessao");
		String entrada = campo(mvc.perform(post("/api/salas/" + sala + "/validar-cpf").contentType(MediaType.APPLICATION_JSON).content("{\"cpf\":\"" + CPF + "\"}")), "tokenEntrada");
		mvc.perform(post("/api/consultas/" + consultaId + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + entrada + "\",\"aceito\":true,\"versaoTermo\":\"v2\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/salas/" + sala + "/token").contentType(MediaType.APPLICATION_JSON).content("{\"tokenEntrada\":\"" + entrada + "\"}")).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + consultaId + "/aceite").header("Authorization", sessao)).andExpect(status().isOk());
		mvc.perform(post("/api/consultas/" + consultaId + "/encerrar").header("Authorization", sessao)).andExpect(status().isOk());
	}

	private UUID jobEnfileirado() {
		AudioTransitorio a = new AudioTransitorio(consultaId, "med-a0", "med00001", consultaId + "/med00001-1.opus.enc", Instant.now().minusSeconds(60));
		a.completar(Instant.now(), 960);
		audios.save(a);
		jobsService.publicarPendentes();
		return jobs.findByConsultaId(consultaId).orElseThrow().getId();
	}

	private void transcricaoPronta() {
		UUID jobId = jobEnfileirado();
		resultados.processar("""
				{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":9000,
				 "segmentos":[{"papel":"MEDICO","inicioMs":100,"fimMs":2000,"texto":"Bom dia, Maria."},
				              {"papel":"PACIENTE","inicioMs":2500,"fimMs":4000,"texto":"Bom dia, doutora."}]}""".formatted(jobId, consultaId));
	}

	private WebhookEntrega entrega(String evento) {
		return entregas.findByConsultaIdAndEvento(consultaId, evento).orElseThrow();
	}

	private void vencer(WebhookEntrega e) {
		jdbc.update("UPDATE webhook_entrega SET proxima_tentativa_em = ? WHERE id = ?", Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC), e.getId());
	}

	@Test
	void entregaATranscricaoAssinadaNoDestinoCadastradoMaisOCaminhoDoAgendamento() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/clinica-42/consultas?tenant=7\"}");
		consultaEncerrada();
		transcricaoPronta();
		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.PENDENTE);

		assertThat(despachante.processarPendentes()).isGreaterThanOrEqualTo(1);

		Recebido r = recebidos.stream().filter(x -> WebhookEventos.CONCLUIDA.equals(x.evento())).findFirst().orElseThrow();
		assertThat(r.caminho()).isEqualTo("/hooks/medilink/clinica-42/consultas?tenant=7"); // base cadastrada + caminho/parâmetros do agendamento
		assertThat(Assinatura.valida(SEGREDO, r.assinatura(), r.corpo().getBytes(StandardCharsets.UTF_8), Instant.now().getEpochSecond(), 300)).isTrue();
		JsonNode c = json.readTree(r.corpo());
		assertThat(c.path("evento").asString()).isEqualTo(WebhookEventos.CONCLUIDA);
		assertThat(c.path("eventoId").asString()).isEqualTo(r.eventoId());
		assertThat(c.path("agendamento").path("chaveReuniao").asString()).isEqualTo(chave);
		assertThat(c.path("agendamento").path("medicoChave").asString()).isEqualTo("CRM-1");
		assertThat(c.path("transcricao").path("idioma").asString()).isEqualTo("pt");
		assertThat(c.path("transcricao").path("texto").asString()).isEqualTo("Médico: Bom dia, Maria.\nPaciente: Bom dia, doutora.");
		assertThat(c.path("transcricao").path("segmentos")).hasSize(2);
		assertThat(c.path("transcricao").path("segmentos").get(0).path("papel").asString()).isEqualTo("MEDICO");
		// Nada do paciente além do que ele mesmo disse na consulta: sem CPF, e-mail nem nome cadastrado.
		assertThat(r.corpo().replace("Maria.", "")).doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("maria@mail").doesNotContain("Maria Souza");

		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.ENTREGUE);
		int antes = recebidos.size();
		despachante.processarPendentes();
		assertThat(recebidos).hasSize(antes); // entregue uma vez só
	}

	@Test
	void semDestinoNoAgendamentoOuSemWebhookAtivoNadaEhEnviado() throws Exception {
		criarReuniao(""); // sem webhook: o fluxo é o de sempre
		consultaEncerrada();
		transcricaoPronta();
		assertThat(entregas.findByConsultaIdAndEvento(consultaId, WebhookEventos.CONCLUIDA)).isEmpty();

		// Caminho informado para um sistema sem webhook cadastrado é recusado na criação.
		var outro = new SistemaIntegrado(integradores, keys);
		mvc.perform(post("/api/integracao/v1/agendamentos").header("X-API-Key", outro.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"medico":{"nome":"Dr","chave":"M1"},"agendamento":{"chave":"x","quando":{"dataHora":"%s"}},"paciente":{"nome":"J"},
						 "webhook":{"caminho":"/x"}}""".formatted(Instant.now().plus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))))
				.andExpect(status().isUnprocessableEntity());
		// Caminho suspeito é recusado.
		mvc.perform(post("/api/integracao/v1/agendamentos").header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"medico":{"nome":"Dr","chave":"M1"},"agendamento":{"chave":"y","quando":{"dataHora":"%s"}},"paciente":{"nome":"J"},
						 "webhook":{"caminho":"//evil.com/x"}}""".formatted(Instant.now().plus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void oCaminhoDoWebhookFicaCifradoNoBanco() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/clinica-42/segredo?token=abc123\"}");

		String guardado = jdbc.queryForObject("SELECT webhook_destino FROM agendamento WHERE id = ?", String.class, UUID.fromString(chave));

		assertThat(guardado).startsWith("enc:").doesNotContain("abc123").doesNotContain("clinica-42");
	}

	@Test
	void falhaTemporariaRepeteComRecuoEDepoisEntrega() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();
		resposta.set(503);
		transcricaoPronta();

		despachante.processarPendentes();

		WebhookEntrega e = entrega(WebhookEventos.CONCLUIDA);
		assertThat(e.getStatus()).isEqualTo(Status.PENDENTE);
		assertThat(e.getTentativas()).isEqualTo(1);
		assertThat(e.getUltimoStatusHttp()).isEqualTo(503);
		assertThat(e.getUltimoErro()).isEqualTo("HTTP_503");
		assertThat(e.getProximaTentativaEm()).isAfter(Instant.now().plusSeconds(30)); // 1º recuo = 1 min
		int tentativasFeitas = recebidos.size();
		despachante.processarPendentes();
		assertThat(recebidos).hasSize(tentativasFeitas); // ainda não venceu: não insiste

		resposta.set(200);
		vencer(e);
		despachante.processarPendentes();

		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.ENTREGUE);
		assertThat(entrega(WebhookEventos.CONCLUIDA).getTentativas()).isEqualTo(2);
		// O receptor recebeu o MESMO evento nas duas tentativas: ele deduplica pelo eventoId.
		assertThat(recebidos.stream().map(Recebido::eventoId).distinct()).hasSize(1);
	}

	@Test
	void erroDoClienteDesisteNaHoraEUmaListaEsgotadaDeRecuosTambem() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();
		resposta.set(400);
		transcricaoPronta();

		despachante.processarPendentes();

		WebhookEntrega e = entrega(WebhookEventos.CONCLUIDA);
		assertThat(e.getStatus()).isEqualTo(Status.FALHA); // 400 não adianta repetir
		assertThat(e.getProximaTentativaEm()).isNull();
		// Reenvio pelo administrador e depois esgotando os recuos (7 tentativas).
		resposta.set(500);
		e.reenfileirar(Instant.now());
		entregas.save(e);
		for (int i = 0; i < 8; i++) {
			vencer(entrega(WebhookEventos.CONCLUIDA));
			despachante.processarPendentes();
		}
		WebhookEntrega fim = entrega(WebhookEventos.CONCLUIDA);
		assertThat(fim.getStatus()).isEqualTo(Status.FALHA);
		assertThat(fim.getTentativas()).isEqualTo(7);
	}

	@Test
	void redirecionamentoNaoEhSeguidoEViraFalha() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();
		resposta.set(302);
		transcricaoPronta();

		despachante.processarPendentes();

		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.FALHA);
		assertThat(entrega(WebhookEventos.CONCLUIDA).getUltimoErro()).isEqualTo("HTTP_302");
	}

	@Test
	void consultaSemAudioAvisaQueATranscricaoEstaIndisponivel() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();

		jobsService.publicarPendentes(); // sem áudio capturado: SEM_AUDIO
		despachante.processarPendentes();

		Recebido r = recebidos.stream().filter(x -> WebhookEventos.INDISPONIVEL.equals(x.evento())).findFirst().orElseThrow();
		JsonNode c = json.readTree(r.corpo());
		assertThat(c.path("motivo").asString()).isEqualTo("SEM_AUDIO");
		assertThat(c.has("transcricao")).isFalse();
		assertThat(entrega(WebhookEventos.INDISPONIVEL).getStatus()).isEqualTo(Status.ENTREGUE);
	}

	@Test
	void transcricaoNaoEntregueNuncaEApagadaPelaRetencao() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();
		transcricaoPronta();
		jdbc.update("UPDATE transcricao SET criada_em = ? WHERE consulta_id = ?", Instant.now().minus(40, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), consultaId);

		retencao.expirarTranscricoes();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transcricao WHERE consulta_id = ?", Integer.class, consultaId)).isEqualTo(1); // pendente: fica

		despachante.processarPendentes();
		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.ENTREGUE);
		retencao.expirarTranscricoes();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transcricao WHERE consulta_id = ?", Integer.class, consultaId)).isZero(); // entregue: segue a política
	}

	@Test
	void integradorDesativadoOuSemSegredoNaoRecebeNada() throws Exception {
		criarReuniao(",\"webhook\":{\"caminho\":\"/c\"}");
		consultaEncerrada();
		transcricaoPronta();
		jdbc.update("UPDATE integrador SET ativo = 0 WHERE id = ?", sistema.integrador.getId());

		despachante.processarPendentes();

		assertThat(recebidos).isEmpty();
		assertThat(entrega(WebhookEventos.CONCLUIDA).getStatus()).isEqualTo(Status.FALHA);
		assertThat(entrega(WebhookEventos.CONCLUIDA).getUltimoErro()).isEqualTo("SEM_DESTINO");
	}
}
