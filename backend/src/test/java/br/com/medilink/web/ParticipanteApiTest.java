package br.com.medilink.web;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.ConsultaParticipanteRepository;
import br.com.medilink.repository.MedicoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T12: o cliente informa o próprio endpoint do Jitsi; o papel vem da autenticação, não do que ele diz. */
@SpringBootTest
@AutoConfigureMockMvc
class ParticipanteApiTest {

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired ConsultaParticipanteRepository participantes;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;

	private CenarioDeConsulta cenario;
	private Consulta c;

	@BeforeEach
	void montar() throws Exception {
		cenario = new CenarioDeConsulta(mvc, medicos);
		c = cenario.criar();
	}

	private ResultActions medico(String email, String endpoint) throws Exception {
		return mvc.perform(post("/api/consultas/" + c.id() + "/participante").with(user(email)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpointId\":\"" + endpoint + "\"}"));
	}

	private ResultActions paciente(String token, String endpoint) throws Exception {
		return mvc.perform(post("/api/consultas/" + c.id() + "/participante").contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpointId\":\"" + endpoint + "\",\"tokenEntrada\":\"" + token + "\"}"));
	}

	@Test
	void medicoEPacienteRegistramSeusEndpointsComOPapelDaAutenticacao() throws Exception {
		medico(cenario.emailMedico, "7bc45eb8").andExpect(status().isOk()).andExpect(jsonPath("$.papel").value("MEDICO"));
		paciente(c.entrada(), "d88aeac9").andExpect(status().isOk()).andExpect(jsonPath("$.papel").value("PACIENTE"));

		var lista = participantes.findByConsultaId(UUID.fromString(c.id()));
		assertThat(lista).extracting("endpointId", "papel").containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple("7bc45eb8", br.com.medilink.domain.PapelParticipante.MEDICO),
				org.assertj.core.groups.Tuple.tuple("d88aeac9", br.com.medilink.domain.PapelParticipante.PACIENTE));
		assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("PARTICIPANTE_REGISTRADO");
	}

	@Test
	void registrarDeNovoEhIdempotente() throws Exception {
		medico(cenario.emailMedico, "abc12345").andExpect(status().isOk());
		medico(cenario.emailMedico, "abc12345").andExpect(status().isOk());

		assertThat(participantes.findByConsultaId(UUID.fromString(c.id()))).hasSize(1);
	}

	@Test
	void umEndpointNaoTrocaDePapel() throws Exception {
		medico(cenario.emailMedico, "abc12345").andExpect(status().isOk());

		paciente(c.entrada(), "abc12345").andExpect(status().isConflict());
		assertThat(participantes.findByConsultaIdAndEndpointId(UUID.fromString(c.id()), "abc12345")).get()
				.extracting("papel").isEqualTo(br.com.medilink.domain.PapelParticipante.MEDICO);
	}

	@Test
	void endpointInvalidoEhRejeitado() throws Exception {
		medico(cenario.emailMedico, "../etc").andExpect(status().isBadRequest());
		medico(cenario.emailMedico, "").andExpect(status().isBadRequest());
		medico(cenario.emailMedico, "x".repeat(65)).andExpect(status().isBadRequest());
	}

	@Test
	void soOMedicoDonoRegistraComoMedico() throws Exception {
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));

		medico(outro, "abc12345").andExpect(status().isNotFound());
		assertThat(participantes.findByConsultaId(UUID.fromString(c.id()))).isEmpty();
	}

	@Test
	void pacienteSemTokenValidoNaoRegistra() throws Exception {
		paciente("lixo", "abc12345").andExpect(status().isForbidden());
		mvc.perform(post("/api/consultas/" + c.id() + "/participante").contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpointId\":\"abc12345\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/consultas/" + UUID.randomUUID() + "/participante").contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpointId\":\"abc12345\",\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isForbidden());
		// Token de entrada de OUTRA sala também não vale.
		Consulta outra = cenario.criar();
		paciente(outra.entrada(), "abc12345").andExpect(status().isForbidden());
		assertThat(participantes.findByConsultaId(UUID.fromString(c.id()))).isEmpty();
	}

	@Test
	void consultaEncerradaNaoAceitaMaisRegistro() throws Exception {
		jdbc.update("UPDATE consulta SET status = 'ENCERRADA' WHERE id = ?", UUID.fromString(c.id()));

		medico(cenario.emailMedico, "abc12345").andExpect(status().isConflict());
	}
}
