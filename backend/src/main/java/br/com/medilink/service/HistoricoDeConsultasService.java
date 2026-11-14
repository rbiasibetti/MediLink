package br.com.medilink.service;

import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.domain.TranscricaoJob;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.repository.TranscricaoRepository;
import br.com.medilink.transcricao.TranscricaoProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lista as consultas do médico autenticado e, para cada uma, se a transcrição está disponível (T20). Só as dele. */
@Service
public class HistoricoDeConsultasService {

	public static final int TAMANHO_MAXIMO = 50;

	/** Situação da transcrição de uma consulta, vista da lista. */
	public enum TranscricaoEstado { NAO_APLICAVEL, EM_PROCESSAMENTO, DISPONIVEL, SEM_AUDIO, FALHA, EXPIRADA }

	public record Resumo(UUID consultaId, UUID agendamentoId, String pacienteNome, Instant dataHora, ConsultaStatus status,
			br.com.medilink.domain.AgendamentoStatus agendamentoStatus, String roomName, Instant inicioEm, Instant fimEm,
			TranscricaoEstado transcricao) {
	}

	public record Pagina(List<Resumo> itens, int pagina, int tamanho, long total, boolean leituraDeTranscricao) {
	}

	private final MedicoRepository medicos;
	private final ConsultaRepository consultas;
	private final TranscricaoRepository transcricoes;
	private final TranscricaoJobRepository jobs;
	private final boolean leituraPeloMedico;

	public HistoricoDeConsultasService(MedicoRepository medicos, ConsultaRepository consultas, TranscricaoRepository transcricoes,
			TranscricaoJobRepository jobs, TranscricaoProperties props) {
		this.leituraPeloMedico = props.leituraPeloMedico();
		this.medicos = medicos;
		this.consultas = consultas;
		this.transcricoes = transcricoes;
		this.jobs = jobs;
	}

	@Transactional(readOnly = true)
	public Pagina listar(String medicoEmail, ConsultaStatus status, int pagina, int tamanho) {
		var medico = medicos.findByEmail(medicoEmail).filter(m -> m.isAtivo())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		var pedido = PageRequest.of(Math.max(0, pagina), Math.min(Math.max(1, tamanho), TAMANHO_MAXIMO), Sort.by(Sort.Direction.DESC, "agendamento.dataHora"));
		Page<Consulta> p = status == null ? consultas.findByAgendamentoMedicoId(medico.getId(), pedido)
				: consultas.findByAgendamentoMedicoIdAndStatus(medico.getId(), status, pedido);
		List<UUID> ids = p.getContent().stream().map(Consulta::getId).toList();
		Set<UUID> comTranscricao = ids.isEmpty() ? Set.of() : new HashSet<>(transcricoes.consultasComTranscricao(ids));
		Map<UUID, TranscricaoJob> jobsPorConsulta = ids.isEmpty() ? Map.of()
				: jobs.findByConsultaIdIn(ids).stream().collect(Collectors.toMap(TranscricaoJob::getConsultaId, Function.identity()));
		List<Resumo> itens = p.getContent().stream().map(c -> new Resumo(c.getId(), c.getAgendamento().getId(), c.getAgendamento().getPacienteNome(),
				c.getAgendamento().getDataHora(), c.getStatus(), c.getAgendamento().getStatus(), c.getAgendamento().getRoomName(),
				c.getInicioEm(), c.getFimEm(), estado(c, comTranscricao.contains(c.getId()), jobsPorConsulta.get(c.getId())))).toList();
		return new Pagina(itens, p.getNumber(), p.getSize(), p.getTotalElements(), leituraPeloMedico);
	}

	static TranscricaoEstado estado(Consulta c, boolean temTranscricao, TranscricaoJob job) {
		if (temTranscricao) {
			return TranscricaoEstado.DISPONIVEL;
		}
		if (c.getStatus() != ConsultaStatus.ENCERRADA && c.getStatus() != ConsultaStatus.TRANSCRITA) {
			return TranscricaoEstado.NAO_APLICAVEL;
		}
		if (job == null) {
			return TranscricaoEstado.EM_PROCESSAMENTO;
		}
		return switch (job.getStatus()) {
			case SEM_AUDIO -> TranscricaoEstado.SEM_AUDIO;
			case FALHA_NO_ENVIO, FALHA_NO_WORKER -> TranscricaoEstado.FALHA;
			case EXPIRADO, CONCLUIDO -> TranscricaoEstado.EXPIRADA; // concluído sem transcrição no banco: apagada pela retenção
			default -> TranscricaoEstado.EM_PROCESSAMENTO;
		};
	}
}
