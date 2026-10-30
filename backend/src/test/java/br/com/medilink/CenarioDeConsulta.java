package br.com.medilink;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.MedicoRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Monta consultas pela API, como os clientes fazem, para os testes de integração. Um médico novo por instância. */
public final class CenarioDeConsulta {

	public static final String CPF = "529.982.247-25";

	public record Consulta(String sala, String id, String entrada) {
	}

	private final MockMvc mvc;
	public final String emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";

	public CenarioDeConsulta(MockMvc mvc, MedicoRepository medicos) {
		this.mvc = mvc;
		medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	/** Cria o agendamento e passa o paciente pela porta do CPF (tem o token de entrada). */
	public Consulta criar() throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","dataHora":"%s"}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS)))).andReturn().getResponse().getContentAsString();
		String sala = campo(json, "roomName");
		String entrada = campo(mvc.perform(post("/api/salas/" + sala + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + CPF + "\"}")).andReturn().getResponse().getContentAsString(), "tokenEntrada");
		return new Consulta(sala, campo(json, "consultaId"), entrada);
	}

	public void consentir(Consulta c, boolean aceito) throws Exception {
		mvc.perform(post("/api/consultas/" + c.id() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"%s\",\"aceito\":%s,\"versaoTermo\":\"v1\"}".formatted(c.entrada(), aceito)))
				.andExpect(status().isOk());
	}

	/** O paciente chega e o médico o aceita: a consulta passa a EM_ANDAMENTO. */
	public void iniciar(Consulta c) throws Exception {
		mvc.perform(post("/api/salas/" + c.sala() + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + c.entrada() + "\"}")).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + c.id() + "/aceite").with(user(emailMedico))).andExpect(status().isOk());
	}

	public void encerrar(Consulta c) throws Exception {
		mvc.perform(post("/api/consultas/" + c.id() + "/encerrar").with(user(emailMedico))).andExpect(status().isOk());
	}

	public static String campo(String json, String nome) {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", nome, json).isTrue();
		return m.group(1);
	}
}
