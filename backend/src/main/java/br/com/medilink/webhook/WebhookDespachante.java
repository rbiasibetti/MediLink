package br.com.medilink.webhook;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.Integrador;
import br.com.medilink.domain.Transcricao;
import br.com.medilink.domain.WebhookEntrega;
import br.com.medilink.domain.WebhookEntrega.Status;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.repository.WebhookEntregaRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.webhook.HttpsPostSeguro.FalhaDeEnvio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envia as entregas pendentes do webhook (T19). Para cada uma: monta o corpo na hora (a partir da transcrição já
 * gravada, cifrada em repouso), compõe o destino (URL base cadastrada + caminho/parâmetros do agendamento), assina e
 * envia pelo {@link HttpsPostSeguro}. Entrega "pelo menos uma vez": o receptor deduplica pelo {@code eventoId}.
 * Nada do conteúdo vai para log; só ids, status HTTP e o tipo do erro.
 */
@Service
public class WebhookDespachante {

	private static final Logger log = LoggerFactory.getLogger(WebhookDespachante.class);
	private static final Duration RESERVA = Duration.ofMinutes(2);
	private static final String ATOR = "webhook";

	private final WebhookEntregaRepository entregas;
	private final IntegradorRepository integradores;
	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final TranscricaoRepository transcricoes;
	private final HttpsPostSeguro http;
	private final WebhookProperties props;
	private final JsonMapper json;
	private final AuditService auditoria;
	private final Clock clock;
	private final TransactionTemplate tx;

	public WebhookDespachante(WebhookEntregaRepository entregas, IntegradorRepository integradores, AgendamentoRepository agendamentos,
			ConsultaRepository consultas, TranscricaoRepository transcricoes, HttpsPostSeguro http, WebhookProperties props,
			JsonMapper json, AuditService auditoria, Clock clock, PlatformTransactionManager txManager) {
		this.entregas = entregas;
		this.integradores = integradores;
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.transcricoes = transcricoes;
		this.http = http;
		this.props = props;
		this.json = json;
		this.auditoria = auditoria;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Envia as vencidas (até 20 por rodada). Devolve quantas foram entregues agora. */
	public int processarPendentes() {
		List<UUID> ids = tx.execute(s -> entregas.vencidas(Instant.now(clock), PageRequest.of(0, 20)).stream().map(WebhookEntrega::getId).toList());
		int entregues = 0;
		for (UUID id : ids) {
			if (enviar(id)) {
				entregues++;
			}
		}
		return entregues;
	}

	private boolean enviar(UUID entregaId) {
		// 1) Reserva: se esta instância cair no meio, outra só repete depois da reserva; e duas não enviam ao mesmo tempo.
		Preparada p = tx.execute(s -> {
			WebhookEntrega e = entregas.findById(entregaId).orElse(null);
			Instant agora = Instant.now(clock);
			if (e == null || e.getStatus() != Status.PENDENTE || e.getProximaTentativaEm() == null || e.getProximaTentativaEm().isAfter(agora)) {
				return null;
			}
			e.reservar(agora.plus(RESERVA));
			entregas.saveAndFlush(e);
			return preparar(e);
		});
		if (p == null) {
			return false;
		}
		if (p.erroPermanente() != null) {
			resultado(entregaId, null, p.erroPermanente(), true);
			return false;
		}
		// 2) Envio, fora de transação.
		try {
			var resposta = http.postar(p.destino(), p.corpo(), p.cabecalhos());
			int st = resposta.status();
			if (st >= 200 && st < 300) {
				tx.executeWithoutResult(s -> {
					WebhookEntrega e = entregas.findById(entregaId).orElseThrow();
					e.entregue(st, Instant.now(clock));
					auditoria.registrar(ATOR, "WEBHOOK_ENTREGUE", "consulta/" + e.getConsultaId());
				});
				return true;
			}
			// 5xx, 429 e 408 repetem; os demais 4xx e qualquer 3xx (nunca seguimos redirecionamento) desistem.
			boolean repete = st >= 500 || st == 429 || st == 408;
			resultado(entregaId, st, "HTTP_" + st, !repete);
		}
		catch (FalhaDeEnvio f) {
			resultado(entregaId, null, f.tipo(), f.permanente());
		}
		return false;
	}

	private void resultado(UUID entregaId, Integer http, String erro, boolean permanente) {
		tx.executeWithoutResult(s -> {
			WebhookEntrega e = entregas.findById(entregaId).orElseThrow();
			Instant agora = Instant.now(clock);
			int proximaTentativa = e.getTentativas(); // índice na lista de recuos (a 1ª falha usa recuos[0])
			if (permanente || proximaTentativa >= props.recuos().size()) {
				e.falhar(http, erro);
				auditoria.registrar(ATOR, "WEBHOOK_FALHA", "consulta/" + e.getConsultaId());
				log.warn("Webhook: entrega {} da consulta {} falhou de vez ({})", e.getId(), e.getConsultaId(), erro);
			}
			else {
				e.repetir(http, erro, agora.plus(props.recuos().get(proximaTentativa)));
				log.warn("Webhook: entrega {} falhou ({}); nova tentativa em {}", e.getId(), erro, props.recuos().get(proximaTentativa));
			}
		});
	}

	private record Preparada(URI destino, byte[] corpo, Map<String, String> cabecalhos, String erroPermanente) {
	}

	/** Monta destino, corpo e cabeçalhos. Qualquer inconsistência de configuração vira falha permanente com um código fixo. */
	private Preparada preparar(WebhookEntrega e) {
		Integrador integrador = integradores.findById(e.getIntegradorId()).orElse(null);
		Agendamento ag = agendamentos.findById(e.getAgendamentoId()).orElse(null);
		Consulta consulta = consultas.findById(e.getConsultaId()).orElse(null);
		if (integrador == null || ag == null || consulta == null || !integrador.isAtivo() || !integrador.isWebhookAtivo()
				|| integrador.getWebhookBaseUrl() == null || integrador.getWebhookSegredo() == null || ag.getWebhookDestino() == null) {
			return new Preparada(null, null, null, "SEM_DESTINO");
		}
		URI destino;
		try {
			destino = WebhookDestino.compor(WebhookDestino.validarBase(integrador.getWebhookBaseUrl(), props.modoDev()), ag.getWebhookDestino());
		}
		catch (RuntimeException ex) {
			return new Preparada(null, null, null, "DESTINO_INVALIDO");
		}
		byte[] corpo;
		try {
			corpo = corpo(e, ag, consulta).getBytes(StandardCharsets.UTF_8);
		}
		catch (SemTranscricao ex) {
			return new Preparada(null, null, null, "SEM_TRANSCRICAO");
		}
		if (corpo.length > props.tamanhoMaximo()) {
			return new Preparada(null, null, null, "CORPO_GRANDE_DEMAIS");
		}
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/json; charset=utf-8");
		h.put("User-Agent", "MediLink-Webhook/1");
		h.put("X-MediLink-Event", e.getEvento());
		h.put("X-MediLink-Event-Id", e.getEventoId().toString());
		h.put("X-MediLink-Delivery", e.getId().toString());
		h.put("X-MediLink-Signature", Assinatura.cabecalho(integrador.getWebhookSegredo(), Instant.now(clock).getEpochSecond(), corpo));
		return new Preparada(destino, corpo, h, null);
	}

	private static final class SemTranscricao extends RuntimeException {
		SemTranscricao() {
			super(null, null, false, false);
		}
	}

	/**
	 * Conteúdo (modo completo). Dados do agendamento = os que o próprio sistema integrado mandou (chaves, médico, horário);
	 * NUNCA CPF, e-mail nem nome do paciente. A transcrição só vai no evento "concluída".
	 */
	private String corpo(WebhookEntrega e, Agendamento ag, Consulta consulta) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("evento", e.getEvento());
		m.put("eventoId", e.getEventoId().toString());
		m.put("criadoEm", e.getCriadoEm().toString());
		Map<String, Object> reuniao = new LinkedHashMap<>();
		reuniao.put("chaveReuniao", ag.getId().toString());
		reuniao.put("referencia", ag.getReferenciaExterna());
		reuniao.put("dataHora", ag.getDataHora().toString());
		reuniao.put("medicoChave", ag.getMedico().getChaveExterna());
		m.put("agendamento", reuniao);
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("status", consulta.getStatus().name());
		c.put("inicioEm", consulta.getInicioEm() == null ? null : consulta.getInicioEm().toString());
		c.put("fimEm", consulta.getFimEm() == null ? null : consulta.getFimEm().toString());
		m.put("consulta", c);
		if (e.getEvento().equals(WebhookEventos.CONCLUIDA)) {
			Transcricao t = transcricoes.findByConsultaId(consulta.getId()).orElseThrow(SemTranscricao::new);
			Map<String, Object> tr = new LinkedHashMap<>();
			tr.put("idioma", t.getIdioma());
			tr.put("duracaoMs", t.getDuracaoMs());
			tr.put("criadaEm", t.getCriadaEm().toString());
			tr.put("texto", t.getTexto());
			JsonNode segmentos = json.readTree(t.getSegmentos());
			tr.put("segmentos", segmentos);
			m.put("transcricao", tr);
		}
		else {
			m.put("motivo", e.getMotivo());
		}
		return json.writeValueAsString(m);
	}
}
