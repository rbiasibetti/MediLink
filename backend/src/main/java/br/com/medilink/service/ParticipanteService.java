package br.com.medilink.service;

import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaParticipante;
import br.com.medilink.domain.PapelParticipante;
import br.com.medilink.repository.ConsultaParticipanteRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.MedicoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Registra qual endpoint do Jitsi é de quem. O JVB só identifica as fontes de áudio pelo endpoint; sem este
 * mapa o worker de transcrição não saberia quem é o médico e quem é o paciente. O papel vem da autenticação
 * (médico dono, ou paciente com o token de entrada da sala), nunca do que o cliente diz. Um endpoint não troca
 * de papel: tentar registrar o endpoint do outro dá 409.
 */
@Service
public class ParticipanteService {

	private final ConsultaRepository consultas;
	private final MedicoRepository medicos;
	private final ConsultaParticipanteRepository participantes;
	private final EntradaTokenService entrada;
	private final AuditService auditoria;
	private final Clock clock;

	public ParticipanteService(ConsultaRepository consultas, MedicoRepository medicos,
			ConsultaParticipanteRepository participantes, EntradaTokenService entrada, AuditService auditoria,
			Clock clock) {
		this.consultas = consultas;
		this.medicos = medicos;
		this.participantes = participantes;
		this.entrada = entrada;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	@Transactional
	public PapelParticipante registrarMedico(String medicoEmail, UUID consultaId, String endpointId) {
		var medico = medicos.findByEmail(medicoEmail).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
		Consulta c = consultas.findById(consultaId)
				.filter(x -> x.getAgendamento().getMedico().getId().equals(medico.getId()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return registrar(c, endpointId, PapelParticipante.MEDICO);
	}

	@Transactional
	public PapelParticipante registrarPaciente(UUID consultaId, String tokenEntrada, String endpointId) {
		Consulta c = consultas.findById(consultaId).orElseThrow(ParticipanteService::negado);
		if (tokenEntrada == null || !entrada.valido(tokenEntrada, c.getAgendamento().getRoomName())) {
			throw negado();
		}
		return registrar(c, endpointId, PapelParticipante.PACIENTE);
	}

	private PapelParticipante registrar(Consulta c, String endpointId, PapelParticipante papel) {
		if (!c.aceitaConsentimento()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "A consulta não está mais aberta.");
		}
		var existente = participantes.findByConsultaIdAndEndpointId(c.getId(), endpointId);
		if (existente.isPresent()) {
			if (existente.get().getPapel() != papel) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Endpoint já registrado por outro participante.");
			}
			return papel;
		}
		participantes.save(new ConsultaParticipante(c.getId(), endpointId, papel, Instant.now(clock)));
		auditoria.registrar(papel == PapelParticipante.MEDICO ? "medico" : "paciente", "PARTICIPANTE_REGISTRADO", "consulta/" + c.getId());
		return papel;
	}

	private static ResponseStatusException negado() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível validar o acesso.");
	}
}
