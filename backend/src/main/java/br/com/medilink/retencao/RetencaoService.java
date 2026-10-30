package br.com.medilink.retencao;

import br.com.medilink.domain.AudioStatus;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.TentativaCpfRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.transcricao.ArmazenamentoDeAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Retenção (T14): nada dos dados sensíveis fica além da finalidade. Cada regra é idempotente e independente;
 * a falha de uma não impede as outras. A auditoria registra o fato (quantos e de qual consulta), nunca o conteúdo.
 * A trilha de auditoria em si não é apagada por aqui (o banco a protege contra UPDATE/DELETE; prazo é do jurídico).
 */
@Service
public class RetencaoService {

	private static final Logger log = LoggerFactory.getLogger(RetencaoService.class);
	private static final String ATOR = "retencao";
	/** Estados em que o job ainda poderia usar o áudio; ao expirar o áudio, viram EXPIRADO. */
	private static final Set<JobStatus> JOBS_QUE_PERDEM_O_AUDIO = Set.of(JobStatus.PENDENTE, JobStatus.ENFILEIRADO,
			JobStatus.FALHA_NO_ENVIO, JobStatus.FALHA_NO_WORKER, JobStatus.TRANSCRITO);

	private final RetencaoProperties props;
	private final TentativaCpfRepository tentativas;
	private final AudioTransitorioRepository audios;
	private final TranscricaoJobRepository jobs;
	private final TranscricaoRepository transcricoes;
	private final ArmazenamentoDeAudio armazenamento;
	private final AuditService auditoria;
	private final Clock clock;
	private final TransactionTemplate tx;

	public RetencaoService(RetencaoProperties props, TentativaCpfRepository tentativas, AudioTransitorioRepository audios,
			TranscricaoJobRepository jobs, TranscricaoRepository transcricoes, ArmazenamentoDeAudio armazenamento,
			AuditService auditoria, Clock clock, PlatformTransactionManager txManager) {
		this.props = props;
		this.tentativas = tentativas;
		this.audios = audios;
		this.jobs = jobs;
		this.transcricoes = transcricoes;
		this.armazenamento = armazenamento;
		this.auditoria = auditoria;
		this.clock = clock;
		this.tx = new TransactionTemplate(txManager);
	}

	/** Roda todas as regras. Cada uma é protegida: um erro numa não impede as outras. */
	public void executar() {
		executarRegra("tentativas de CPF", this::limparTentativasDeCpf);
		executarRegra("áudio vencido", this::expirarAudios);
		executarRegra("transcrição vencida", this::expirarTranscricoes);
	}

	private void executarRegra(String nome, Runnable regra) {
		try {
			regra.run();
		}
		catch (RuntimeException e) {
			log.warn("Retenção: a regra '{}' falhou ({}); será tentada de novo", nome, e.getClass().getSimpleName());
		}
	}

	/** Apaga as tentativas falhas de CPF mais antigas que o prazo. Devolve quantas. */
	public int limparTentativasDeCpf() {
		Instant limite = Instant.now(clock).minus(props.tentativaCpf());
		Integer apagadas = tx.execute(s -> tentativas.deleteByMomentoBefore(limite));
		return apagadas == null ? 0 : apagadas;
	}

	/**
	 * Apaga os arquivos de áudio que passaram do teto de guarda, qualquer que seja o estado do job, e marca o job
	 * como {@code EXPIRADO} quando ele ainda dependia do áudio. Devolve quantos arquivos foram apagados.
	 */
	public int expirarAudios() {
		Instant limite = Instant.now(clock).minus(props.audioMaximo());
		int apagados = 0;
		for (AudioTransitorio a : audios.findByStatusNotAndIniciadoEmBefore(AudioStatus.DESCARTADO, limite)) {
			try {
				armazenamento.apagar(a.getConsultaId(), a.getArquivo());
			}
			catch (IOException | RuntimeException e) {
				log.warn("Retenção: não foi possível apagar um áudio vencido da consulta {} ({}); será repetido",
						a.getConsultaId(), e.getClass().getSimpleName());
				continue;
			}
			UUID audioId = a.getId();
			UUID consultaId = a.getConsultaId();
			tx.executeWithoutResult(s -> {
				audios.findById(audioId).ifPresent(x -> x.descartar(Instant.now(clock)));
				jobs.findByConsultaId(consultaId).filter(j -> JOBS_QUE_PERDEM_O_AUDIO.contains(j.getStatus()))
						.ifPresent(j -> j.marcar(JobStatus.EXPIRADO, Instant.now(clock)));
				auditoria.registrar(ATOR, "AUDIO_EXPIRADO", "consulta/" + consultaId);
			});
			armazenamento.removerPastaVazia(consultaId);
			apagados++;
		}
		return apagados;
	}

	/** Apaga as transcrições mais antigas que o prazo (se houver prazo). Devolve quantas. */
	public int expirarTranscricoes() {
		Duration prazo = props.transcricao();
		if (prazo.isZero() || prazo.isNegative()) {
			return 0;
		}
		Instant limite = Instant.now(clock).minus(prazo);
		List<UUID> consultas = transcricoes.consultasComTranscricaoCriadaAntesDe(limite);
		for (UUID consultaId : consultas) {
			tx.executeWithoutResult(s -> {
				transcricoes.deleteByConsultaId(consultaId);
				auditoria.registrar(ATOR, "TRANSCRICAO_EXPIRADA", "consulta/" + consultaId);
			});
		}
		return consultas.size();
	}
}
