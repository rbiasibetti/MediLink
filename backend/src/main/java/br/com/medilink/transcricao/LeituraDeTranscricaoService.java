package br.com.medilink.transcricao;

import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.domain.PapelParticipante;
import br.com.medilink.domain.Transcricao;
import br.com.medilink.domain.TranscricaoJob;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.service.ConsultaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura da transcrição pelo médico dono da consulta. Cada leitura é auditada (sem o conteúdo). Só funciona com
 * {@code medilink.transcricao.leitura-pelo-medico=true}: o texto é decifrado ao carregar, a cada requisição.
 */
@Service
public class LeituraDeTranscricaoService {

	public record SegmentoDto(PapelParticipante papel, long inicioMs, long fimMs, String texto) {
	}

	public record TranscricaoDto(UUID consultaId, String idioma, long duracaoMs, Instant criadaEm, String texto,
			List<SegmentoDto> segmentos) {
	}

	private final ConsultaService consultas;
	private final TranscricaoRepository transcricoes;
	private final TranscricaoJobRepository jobs;
	private final AuditService auditoria;
	private final JsonMapper json;

	private static final Logger log = LoggerFactory.getLogger(LeituraDeTranscricaoService.class);

	private final boolean habilitada;

	public LeituraDeTranscricaoService(ConsultaService consultas, TranscricaoRepository transcricoes, TranscricaoJobRepository jobs,
			AuditService auditoria, JsonMapper json, TranscricaoProperties props) {
		this.habilitada = props.leituraPeloMedico();
		log.info("Leitura da transcrição pelo médico: {}", habilitada ? "HABILITADA" : "desabilitada (medilink.transcricao.leitura-pelo-medico)");
		this.consultas = consultas;
		this.transcricoes = transcricoes;
		this.jobs = jobs;
		this.auditoria = auditoria;
		this.json = json;
	}

	/**
	 * A transcrição, ou vazio se ainda está em processamento (a API responde 202). Consulta de outro médico é 404;
	 * consulta aberta e transcrição que não virá (sem áudio, falha) são 409/404 com a razão.
	 */
	@Transactional
	public Optional<TranscricaoDto> obter(String medicoEmail, UUID consultaId) {
		Consulta consulta;
		try {
			consulta = consultas.daMedico(medicoEmail, consultaId);
		}
		catch (ResponseStatusException negado) {
			// Consulta de outro médico (ou inexistente): a tentativa fica registrada, ainda que a resposta seja 404.
			auditoria.registrarEmTransacaoPropria(medicoEmail, "TRANSCRICAO_ACESSO_NEGADO", "consulta/" + consultaId);
			throw negado;
		}
		if (!habilitada) { // nem chega a carregar (e decifrar) o texto
			auditoria.registrarEmTransacaoPropria(medicoEmail, "TRANSCRICAO_LEITURA_DESABILITADA", "consulta/" + consultaId);
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A leitura de transcrições pela plataforma está desabilitada.");
		}
		Optional<Transcricao> pronta = transcricoes.findByConsultaId(consultaId);
		if (pronta.isPresent()) {
			auditoria.registrar(medicoEmail, "TRANSCRICAO_LIDA", "consulta/" + consultaId);
			return pronta.map(this::paraDto);
		}
		if (consulta.getStatus() != ConsultaStatus.ENCERRADA) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A consulta ainda não foi encerrada.");
		}
		TranscricaoJob job = jobs.findByConsultaId(consultaId).orElse(null);
		if (job == null) {
			return Optional.empty();
		}
		return switch (job.getStatus()) {
			case SEM_AUDIO -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "A consulta não teve áudio capturado: não há transcrição.");
			case FALHA_NO_ENVIO, FALHA_NO_WORKER -> throw new ResponseStatusException(HttpStatus.CONFLICT,
					"A transcrição falhou; o áudio foi mantido para nova tentativa.");
			case EXPIRADO -> throw new ResponseStatusException(HttpStatus.GONE,
					"O áudio expirou sem ser transcrito; não há transcrição desta consulta.");
			case CONCLUIDO -> throw new ResponseStatusException(HttpStatus.GONE,
					"A transcrição desta consulta expirou pela política de retenção.");
			default -> Optional.empty();
		};
	}

	private TranscricaoDto paraDto(Transcricao t) {
		List<SegmentoDto> segmentos = json.readValue(t.getSegmentos(), new TypeReference<List<SegmentoDto>>() {
		});
		return new TranscricaoDto(t.getConsulta().getId(), t.getIdioma(), t.getDuracaoMs(), t.getCriadaEm(), t.getTexto(), segmentos);
	}
}
