package br.com.medilink.transcricao;

import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.domain.PapelParticipante;
import br.com.medilink.domain.Transcricao;
import br.com.medilink.domain.TranscricaoJob;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.webhook.WebhookEventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T13 / RF-07: recebe o resultado do worker, grava a {@link Transcricao} e só então apaga o áudio.
 * <p>
 * Ordem, que é o que torna o descarte seguro: (1) a transcrição e o estado {@code TRANSCRITO} do job são gravados
 * numa transação; (2) depois do commit, cada arquivo é apagado do disco (arquivo ausente conta como apagado) e a
 * linha marcada {@code DESCARTADO}; (3) com todos apagados, o job vira {@code CONCLUIDO}. Se algo falha antes do
 * commit, nada foi apagado; se falha depois, o job fica {@code TRANSCRITO} e a varredura repete só o passo 2.
 * O texto da consulta nunca vai para log nem para a auditoria: aqui só circulam identificadores e tipos de erro.
 */
@Service
public class ResultadoDaTranscricaoService {

	private static final Logger log = LoggerFactory.getLogger(ResultadoDaTranscricaoService.class);
	private static final String ATOR = "transcricao";
	private static final int MAX_SEGMENTOS = 50_000;
	private static final int MAX_TEXTO_DO_SEGMENTO = 20_000;

	public enum Desfecho {
		/** Transcrição gravada agora. */
		GRAVADA,
		/** O mesmo resultado já havia sido processado (entrega repetida da fila). */
		JA_PROCESSADA,
		/** O worker informou que falhou; nada foi gravado e o áudio foi mantido. */
		FALHA_NO_WORKER,
		/** Job desconhecido ou em estado que não espera resultado. */
		IGNORADA
	}

	/** Mensagem fora do contrato. {@code motivo} é um código fixo, nunca um pedaço do conteúdo. */
	public static class ResultadoInvalidoException extends RuntimeException {

		public ResultadoInvalidoException(String motivo) {
			super(motivo, null, false, false);
		}
	}

	private record Segmento(PapelParticipante papel, long inicioMs, long fimMs, String texto) {
	}

	private record Resultado(UUID jobId, UUID consultaId, boolean concluido, String idioma, long duracaoMs,
			List<Segmento> segmentos, String erro) {
	}

	private final TranscricaoJobRepository jobs;
	private final TranscricaoRepository transcricoes;
	private final AudioTransitorioRepository audios;
	private final ConsultaRepository consultas;
	private final AuditService auditoria;
	private final ArmazenamentoDeAudio armazenamento;
	private final TranscricaoProperties props;
	private final WebhookEventos webhooks;
	private final JsonMapper json;
	private final Clock clock;
	private final TransactionTemplate tx;

	public ResultadoDaTranscricaoService(TranscricaoJobRepository jobs, TranscricaoRepository transcricoes,
			AudioTransitorioRepository audios, ConsultaRepository consultas, AuditService auditoria,
			ArmazenamentoDeAudio armazenamento, TranscricaoProperties props, JsonMapper json, Clock clock, PlatformTransactionManager txManager,
			WebhookEventos webhooks) {
		this.jobs = jobs;
		this.transcricoes = transcricoes;
		this.audios = audios;
		this.consultas = consultas;
		this.auditoria = auditoria;
		this.armazenamento = armazenamento;
		this.props = props;
		this.webhooks = webhooks;
		this.json = json;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	/**
	 * Processa uma mensagem do tópico de resultados. Idempotente: a entrega "pelo menos uma vez" pode repetir.
	 * Mensagem inválida lança {@link ResultadoInvalidoException} (não adianta repetir); falha de banco ou de disco
	 * lança a exceção original, e a mensagem volta a ser tentada.
	 */
	public Desfecho processar(String mensagem) {
		Resultado r = ler(mensagem);
		Desfecho desfecho = tx.execute(status -> registrar(r));
		if (desfecho == Desfecho.GRAVADA || desfecho == Desfecho.JA_PROCESSADA) {
			descartarAudio(r.jobId());
		}
		return desfecho;
	}

	private Desfecho registrar(Resultado r) {
		TranscricaoJob job = jobs.findById(r.jobId()).orElse(null);
		if (job == null || !job.getConsultaId().equals(r.consultaId())) {
			log.warn("Transcrição: resultado do job {} não corresponde a nenhum job conhecido", r.jobId());
			return Desfecho.IGNORADA;
		}
		switch (job.getStatus()) {
			case TRANSCRITO, CONCLUIDO -> {
				return Desfecho.JA_PROCESSADA;
			}
			case SEM_AUDIO, FALHA_NO_ENVIO, EXPIRADO -> {
				return Desfecho.IGNORADA;
			}
			default -> {
			}
		}
		Instant agora = Instant.now(clock);
		String recurso = "consulta/" + job.getConsultaId();
		if (!r.concluido()) {
			job.falhouNoWorker(r.erro(), agora);
			auditoria.registrar(ATOR, "TRANSCRICAO_FALHOU", recurso);
			webhooks.registrar(job.getConsultaId(), WebhookEventos.INDISPONIVEL, "FALHA_NO_WORKER");
			log.warn("Transcrição: o worker falhou na consulta {} ({}); o áudio foi mantido", job.getConsultaId(), r.erro());
			return Desfecho.FALHA_NO_WORKER;
		}
		if (transcricoes.findByConsultaId(job.getConsultaId()).isEmpty()) {
			Consulta consulta = consultas.findById(job.getConsultaId()).orElseThrow();
			transcricoes.save(new Transcricao(consulta, texto(r.segmentos()), segmentosEmJson(r.segmentos()), r.duracaoMs(),
					r.idioma(), agora));
		}
		job.marcar(JobStatus.TRANSCRITO, agora);
		auditoria.registrar(ATOR, "TRANSCRICAO_GRAVADA", recurso);
		webhooks.registrar(job.getConsultaId(), WebhookEventos.CONCLUIDA, null);
		return Desfecho.GRAVADA;
	}

	/**
	 * Apaga os arquivos de áudio da consulta de um job {@code TRANSCRITO} e o conclui. Pode ser chamado quantas vezes
	 * for preciso. Um arquivo que o sink ainda diz estar gravando é esperado, até o prazo de {@code esperaMaximaDoAudio}
	 * depois do fim da consulta (para uma linha presa em {@code GRAVANDO} não travar o descarte para sempre).
	 *
	 * @return {@code true} se o job ficou {@code CONCLUIDO} nesta chamada
	 */
	public boolean descartarAudio(UUID jobId) {
		TranscricaoJob job = jobs.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != JobStatus.TRANSCRITO) {
			return false;
		}
		UUID consultaId = job.getConsultaId();
		Consulta consulta = consultas.findById(consultaId).orElseThrow();
		Instant agora = Instant.now(clock);
		Instant fim = consulta.getFimEm() != null ? consulta.getFimEm() : job.getCriadoEm();
		boolean esperaVencida = !agora.isBefore(fim.plus(props.esperaMaximaDoAudio()));

		boolean tudoApagado = true;
		for (AudioTransitorio a : audios.findByConsultaIdOrderByIniciadoEm(consultaId)) {
			if (a.getStatus() == AudioStatus.DESCARTADO) {
				continue;
			}
			if (a.getStatus() == AudioStatus.GRAVANDO && !esperaVencida) {
				tudoApagado = false;
				continue;
			}
			try {
				armazenamento.apagar(consultaId, a.getArquivo());
			}
			catch (IOException | RuntimeException e) {
				log.warn("Transcrição: não foi possível apagar um áudio da consulta {} ({}); será repetido", consultaId,
						e.getClass().getSimpleName());
				tudoApagado = false;
				continue;
			}
			UUID audioId = a.getId();
			tx.executeWithoutResult(s -> audios.findById(audioId).ifPresent(x -> x.descartar(Instant.now(clock))));
		}
		if (!tudoApagado) {
			return false;
		}
		armazenamento.removerPastaVazia(consultaId);
		tx.executeWithoutResult(s -> {
			TranscricaoJob atual = jobs.findById(jobId).orElseThrow();
			atual.marcar(JobStatus.CONCLUIDO, Instant.now(clock));
			auditoria.registrar(ATOR, "AUDIO_DESCARTADO", "consulta/" + consultaId);
		});
		return true;
	}

	/** Repete o descarte dos jobs que gravaram a transcrição mas não conseguiram apagar tudo. Devolve quantos concluíram. */
	public int descartarPendentes() {
		int concluidos = 0;
		for (TranscricaoJob job : jobs.findByStatusOrderByCriadoEm(JobStatus.TRANSCRITO)) {
			if (descartarAudio(job.getId())) {
				concluidos++;
			}
		}
		return concluidos;
	}

	// --- leitura e validação da mensagem do worker (contrato em docs/plan.md) ---

	private Resultado ler(String mensagem) {
		JsonNode m;
		try {
			m = json.readTree(mensagem);
		}
		catch (RuntimeException e) {
			throw new ResultadoInvalidoException("json-invalido");
		}
		if (m == null || !m.isObject() || m.path("versao").asInt(-1) != 1) {
			throw new ResultadoInvalidoException("versao-nao-suportada");
		}
		UUID jobId = uuid(m.path("jobId"));
		UUID consultaId = uuid(m.path("consultaId"));
		String status = m.path("status").asString("");
		if (!status.equals("CONCLUIDO") && !status.equals("FALHA")) {
			throw new ResultadoInvalidoException("status-desconhecido");
		}
		if (status.equals("FALHA")) {
			String erro = m.path("erro").asString("");
			return new Resultado(jobId, consultaId, false, "", 0, List.of(), erro.isBlank() ? "Desconhecido" : erro);
		}
		String idioma = m.path("idioma").asString("");
		if (idioma.isBlank() || idioma.length() > 10) {
			throw new ResultadoInvalidoException("idioma-invalido");
		}
		long duracaoMs = m.path("duracaoMs").asLong(0);
		JsonNode lista = m.path("segmentos");
		if (!lista.isArray() || lista.size() > MAX_SEGMENTOS || duracaoMs < 0) {
			throw new ResultadoInvalidoException("segmentos-invalidos");
		}
		List<Segmento> segmentos = new ArrayList<>(lista.size());
		for (JsonNode s : lista) {
			long inicio = s.path("inicioMs").asLong(-1);
			long fim = s.path("fimMs").asLong(-1);
			String texto = s.path("texto").asString(null);
			if (inicio < 0 || fim < inicio || texto == null || texto.length() > MAX_TEXTO_DO_SEGMENTO) {
				throw new ResultadoInvalidoException("segmento-invalido");
			}
			segmentos.add(new Segmento(papel(s.path("papel").asString("")), inicio, fim, texto.strip()));
		}
		segmentos.sort(Comparator.comparingLong(Segmento::inicioMs)); // estável: empates mantêm a ordem do worker
		return new Resultado(jobId, consultaId, true, idioma, duracaoMs, segmentos, null);
	}

	private static UUID uuid(JsonNode no) {
		try {
			return UUID.fromString(no.asString(""));
		}
		catch (IllegalArgumentException e) {
			throw new ResultadoInvalidoException("identificador-invalido");
		}
	}

	private static PapelParticipante papel(String valor) {
		try {
			return PapelParticipante.valueOf(valor);
		}
		catch (IllegalArgumentException e) {
			return PapelParticipante.DESCONHECIDO;
		}
	}

	private static String texto(List<Segmento> segmentos) {
		StringBuilder sb = new StringBuilder();
		for (Segmento s : segmentos) {
			if (s.texto().isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append('\n');
			}
			sb.append(switch (s.papel()) {
				case MEDICO -> "Médico";
				case PACIENTE -> "Paciente";
				default -> "Participante";
			}).append(": ").append(s.texto());
		}
		return sb.toString();
	}

	private String segmentosEmJson(List<Segmento> segmentos) {
		List<Map<String, Object>> saida = new ArrayList<>(segmentos.size());
		for (Segmento s : segmentos) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("papel", s.papel().name());
			m.put("inicioMs", s.inicioMs());
			m.put("fimMs", s.fimMs());
			m.put("texto", s.texto());
			saida.add(m);
		}
		return json.writeValueAsString(saida);
	}
}
