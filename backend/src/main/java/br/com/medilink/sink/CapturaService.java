package br.com.medilink.sink;

import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Acesso ao banco do audio sink: de qual consulta é a sala, se a captura está autorizada e o registro do áudio. */
@Service
class CapturaService {

	private static final String ATOR = "audio-sink";

	private final AgendamentoRepository agendamentos;
	private final ConsultaRepository consultas;
	private final AudioTransitorioRepository audios;
	private final AuditService auditoria;
	private final Clock clock;

	CapturaService(AgendamentoRepository agendamentos, ConsultaRepository consultas, AudioTransitorioRepository audios,
			AuditService auditoria, Clock clock) {
		this.agendamentos = agendamentos;
		this.consultas = consultas;
		this.audios = audios;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	Optional<UUID> consultaDaSala(String roomName) {
		return agendamentos.findByRoomName(roomName).flatMap(a -> consultas.findByAgendamentoId(a.getId())).map(c -> c.getId());
	}

	/** O portão do consentimento (RF-08): consulta em andamento E consentimento vigente do paciente. */
	@Transactional(readOnly = true)
	boolean autorizada(UUID consultaId) {
		return consultas.findById(consultaId).map(c -> c.capturaAutorizada()).orElse(false);
	}

	@Transactional
	AudioTransitorio abrir(UUID consultaId, String fonte, String endpointId, String arquivo) {
		AudioTransitorio a = audios.save(new AudioTransitorio(consultaId, fonte, endpointId, arquivo, Instant.now(clock)));
		auditoria.registrar(ATOR, "CAPTURA_ABERTA", "consulta/" + consultaId);
		return a;
	}

	@Transactional
	void completar(UUID audioId, long amostras) {
		audios.findById(audioId).ifPresent(a -> {
			a.completar(Instant.now(clock), amostras);
			auditoria.registrar(ATOR, "CAPTURA_FECHADA", "consulta/" + a.getConsultaId());
		});
	}
}
