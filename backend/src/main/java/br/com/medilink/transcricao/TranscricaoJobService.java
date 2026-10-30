package br.com.medilink.transcricao;

import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaParticipante;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.domain.PapelParticipante;
import br.com.medilink.domain.TranscricaoJob;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.ConsultaParticipanteRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Job de transcrição (RF-06): criado ao encerrar a consulta e enviado à fila quando os arquivos de áudio já
 * fecharam. Entrega "pelo menos uma vez": se o envio der certo e o commit falhar, o job é reenviado e o worker
 * descarta o repetido pelo {@code jobId}. Nada do conteúdo (áudio ou texto) passa por aqui, só referências.
 */
@Service
public class TranscricaoJobService {

	private static final Logger log = LoggerFactory.getLogger(TranscricaoJobService.class);
	private static final String ATOR = "transcricao";

	private final TranscricaoJobRepository jobs;
	private final AudioTransitorioRepository audios;
	private final ConsultaRepository consultas;
	private final ConsultaParticipanteRepository participantes;
	private final PublicadorDeJobs publicador;
	private final TranscricaoProperties props;
	private final JsonMapper json;
	private final AuditService auditoria;
	private final Clock clock;
	private final TransactionTemplate tx;

	public TranscricaoJobService(TranscricaoJobRepository jobs, AudioTransitorioRepository audios, ConsultaRepository consultas,
			ConsultaParticipanteRepository participantes, PublicadorDeJobs publicador, TranscricaoProperties props,
			JsonMapper json, AuditService auditoria, Clock clock, PlatformTransactionManager txManager) {
		this.jobs = jobs;
		this.audios = audios;
		this.consultas = consultas;
		this.participantes = participantes;
		this.publicador = publicador;
		this.props = props;
		this.json = json;
		this.auditoria = auditoria;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Chamado ao encerrar a consulta, na mesma transação. Idempotente: um job por consulta. */
	@Transactional
	public void criarAoEncerrar(UUID consultaId) {
		if (jobs.findByConsultaId(consultaId).isEmpty()) {
			jobs.save(new TranscricaoJob(consultaId, Instant.now(clock)));
		}
	}

	/** Varre os jobs pendentes e envia os que estão prontos. Devolve quantos foram enfileirados. */
	public int publicarPendentes() {
		int enviados = 0;
		for (TranscricaoJob pendente : jobs.findByStatusOrderByCriadoEm(JobStatus.PENDENTE)) {
			Boolean enviado = tx.execute(status -> processar(pendente.getId()));
			if (Boolean.TRUE.equals(enviado)) {
				enviados++;
			}
		}
		return enviados;
	}

	private boolean processar(UUID jobId) {
		TranscricaoJob job = jobs.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != JobStatus.PENDENTE) {
			return false;
		}
		Instant agora = Instant.now(clock);
		Consulta consulta = consultas.findById(job.getConsultaId()).orElseThrow();
		List<AudioTransitorio> disponiveis = audios.findByConsultaIdOrderByIniciadoEm(consulta.getId()).stream()
				.filter(a -> a.getStatus() != AudioStatus.DESCARTADO).toList();

		if (disponiveis.isEmpty()) {
			job.marcar(JobStatus.SEM_AUDIO, agora);
			auditoria.registrar(ATOR, "TRANSCRICAO_SEM_AUDIO", "consulta/" + consulta.getId());
			return false;
		}
		boolean aindaGravando = disponiveis.stream().anyMatch(a -> a.getStatus() == AudioStatus.GRAVANDO);
		Instant fim = consulta.getFimEm() != null ? consulta.getFimEm() : job.getCriadoEm();
		if (aindaGravando && agora.isBefore(fim.plus(props.esperaMaximaDoAudio()))) {
			return false; // o sink ainda está fechando os arquivos
		}

		try {
			publicador.publicar(consulta.getId().toString(), mensagem(job, consulta, disponiveis));
		}
		catch (Exception e) {
			job.registrarFalha(e.getClass().getSimpleName(), agora); // só o tipo, nunca a mensagem
			if (job.getTentativas() >= props.maxTentativas()) {
				job.marcar(JobStatus.FALHA_NO_ENVIO, agora);
				log.error("Transcrição: desistiu de enviar o job da consulta {} após {} tentativas", consulta.getId(), job.getTentativas());
			}
			else {
				log.warn("Transcrição: envio do job da consulta {} falhou ({}); será repetido", consulta.getId(), e.getClass().getSimpleName());
			}
			return false;
		}
		job.marcar(JobStatus.ENFILEIRADO, agora);
		auditoria.registrar(ATOR, "TRANSCRICAO_ENFILEIRADA", "consulta/" + consulta.getId());
		return true;
	}

	/** Contrato com o worker (docs/plan.md, "Fila de transcrição"). Só referências: nada de áudio nem de texto. */
	private String mensagem(TranscricaoJob job, Consulta consulta, List<AudioTransitorio> arquivos) {
		Map<String, PapelParticipante> papeis = participantes.findByConsultaId(consulta.getId()).stream()
				.collect(Collectors.toMap(ConsultaParticipante::getEndpointId, ConsultaParticipante::getPapel, (a, b) -> a));
		List<Map<String, Object>> fontes = new ArrayList<>();
		for (AudioTransitorio a : arquivos) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("audioId", a.getId().toString());
			f.put("arquivo", a.getArquivo());
			f.put("endpointId", a.getEndpointId());
			f.put("papel", papeis.getOrDefault(a.getEndpointId(), PapelParticipante.DESCONHECIDO).name());
			f.put("iniciadoEm", a.getIniciadoEm().toString());
			fontes.add(f);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("versao", 1);
		m.put("jobId", job.getId().toString());
		m.put("consultaId", consulta.getId().toString());
		m.put("idioma", "pt");
		m.put("encerradaEm", consulta.getFimEm() == null ? null : consulta.getFimEm().toString());
		m.put("fontes", fontes);
		return json.writeValueAsString(m);
	}
}
