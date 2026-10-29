package br.com.medilink.repository;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.AgendamentoStatus;
import br.com.medilink.domain.AuditLog;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import br.com.medilink.domain.Medico;
import br.com.medilink.domain.Transcricao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T03: as migrações aplicam sobre o schema validado pelo Hibernate e o modelo faz round-trip. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ModeloDadosTest {

	@Autowired MedicoRepository medicos;
	@Autowired AgendamentoRepository agendamentos;
	@Autowired ConsultaRepository consultas;
	@Autowired TranscricaoRepository transcricoes;
	@Autowired AuditLogRepository auditoria;

	private static final String HMAC = "a".repeat(64);

	private Agendamento novoAgendamento(Medico medico, String room) {
		return agendamentos.saveAndFlush(new Agendamento(medico, "Maria Souza", HMAC, null,
				Instant.parse("2026-11-03T14:00:00Z"), room, true, Instant.now()));
	}

	@Test
	void persisteCadeiaCompletaDoMedicoATranscricao() {
		Medico medico = medicos.saveAndFlush(new Medico("Dra. Ana", "CRM-SP 123456", "ana@clinica.test"));
		Agendamento agendamento = novoAgendamento(medico, "consulta-abc");
		Consulta consulta = consultas.saveAndFlush(new Consulta(agendamento));
		transcricoes.saveAndFlush(new Transcricao(consulta, "Médico: bom dia.\nPaciente: bom dia.", "pt", Instant.now()));

		assertThat(agendamentos.findByRoomName("consulta-abc")).get()
				.extracting(Agendamento::getStatus, Agendamento::isCpfValidationEnabled)
				.containsExactly(AgendamentoStatus.AGENDADO, true);
		assertThat(consultas.findByAgendamentoId(agendamento.getId())).get()
				.extracting(Consulta::getStatus).isEqualTo(ConsultaStatus.CRIADA);
		assertThat(transcricoes.findByConsultaId(consulta.getId())).get()
				.extracting(Transcricao::getTexto).asString().contains("Paciente: bom dia.");
	}

	@Test
	void roomNameEhUnico() {
		Medico medico = medicos.saveAndFlush(new Medico("Dr. Bruno", "CRM-RJ 999", "bruno@clinica.test"));
		novoAgendamento(medico, "consulta-dup");

		assertThatThrownBy(() -> novoAgendamento(medico, "consulta-dup"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void umaTranscricaoPorConsulta() {
		Medico medico = medicos.saveAndFlush(new Medico("Dra. Carla", "CRM-MG 55", "carla@clinica.test"));
		Consulta consulta = consultas.saveAndFlush(new Consulta(novoAgendamento(medico, "consulta-t")));
		transcricoes.saveAndFlush(new Transcricao(consulta, "primeira", "pt", Instant.now()));

		assertThatThrownBy(() -> transcricoes.saveAndFlush(new Transcricao(consulta, "segunda", "pt", Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void registraAuditoriaPorRecurso() {
		auditoria.saveAndFlush(new AuditLog("ana@clinica.test", "AGENDAMENTO_CRIADO", "agendamento/1", Instant.now()));

		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/1")).hasSize(1);
	}
}
