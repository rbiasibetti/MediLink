package br.com.medilink.web;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T07: o médico entra como moderador; o paciente só obtém credencial depois da porta do CPF. */
@SpringBootTest
@AutoConfigureMockMvc
class SessaoJitsiApiTest {

	private static final String CPF = "529.982.247-25";
	private static final String SEGREDO_JITSI = "segredo-jitsi-de-teste-com-32-ou-mais-chars";

	@Autowired MockMvc mvc;
	@Autowired MedicoRepository medicos;
	@Autowired AuditLogRepository auditoria;

	private String emailMedico;
	private Medico medico;

	@BeforeEach
	void medico() {
		emailMedico = "dra-" + UUID.randomUUID() + "@clinica.test";
		medico = medicos.save(new Medico("Dra. Ana Lima", "CRM-SP 123456", emailMedico));
	}

	private record Sala(String room, String id, String consultaId) {
	}

	private Sala criar(boolean cpfValidation) throws Exception {
		String json = mvc.perform(post("/api/agendamentos").with(user(emailMedico)).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"pacienteNome":"Maria Souza","pacienteCpf":"%s","pacienteEmail":"maria@mail.test","dataHora":"%s","cpfValidationEnabled":%s}
						""".formatted(CPF, Instant.now().plus(1, ChronoUnit.DAYS), cpfValidation)))
				.andReturn().getResponse().getContentAsString();
		Matcher room = Pattern.compile("\"roomName\":\"([^\"]+)\"").matcher(json);
		Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
		Matcher consulta = Pattern.compile("\"consultaId\":\"([^\"]+)\"").matcher(json);
		assertThat(room.find() && id.find() && consulta.find()).isTrue();
		return new Sala(room.group(1), id.group(1), consulta.group(1));
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		String json = r.andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("campo %s em %s", nome, json).isTrue();
		return m.group(1);
	}

	private String tokenDeEntrada(Sala s, String cpf) throws Exception {
		String entrada = campo(mvc.perform(post("/api/salas/" + s.room() + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"" + cpf + "\"}")).andExpect(status().isOk()), "tokenEntrada");
		consentir(s, entrada);
		return entrada;
	}

	/** Sem o consentimento ao termo o paciente não chega à espera: o fluxo real é CPF, termo, espera. */
	private void consentir(Sala s, String entrada) throws Exception {
		mvc.perform(post("/api/consultas/" + s.consultaId() + "/consentimento").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + entrada + "\",\"aceito\":true,\"versaoTermo\":\"v2\"}")).andExpect(status().isOk());
	}

	private ResultActions trocar(String room, String tokenEntrada) throws Exception {
		return mvc.perform(post("/api/salas/" + room + "/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"tokenEntrada\":\"" + tokenEntrada + "\"}"));
	}

	/** O paciente chega (fica em espera) e o médico o aceita: só então a troca devolve o JWT. */
	private void admitir(Sala s, String entrada) throws Exception {
		trocar(s.room(), entrada).andExpect(status().isAccepted());
		mvc.perform(post("/api/consultas/" + s.consultaId() + "/aceite").with(user(emailMedico))).andExpect(status().isOk());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> usuarioDe(JWTClaimsSet c) {
		return (Map<String, Object>) ((Map<String, Object>) c.getClaim("context")).get("user");
	}

	private static JWTClaimsSet claims(String jwt) throws Exception {
		SignedJWT j = SignedJWT.parse(jwt);
		assertThat(j.verify(new MACVerifier(SEGREDO_JITSI.getBytes(StandardCharsets.UTF_8)))).isTrue();
		return j.getJWTClaimsSet();
	}

	@Test
	void medicoRecebeJwtDeModeradorDaSalaDoAgendamento() throws Exception {
		Sala s = criar(true);

		ResultActions r = mvc.perform(post("/api/agendamentos/" + s.id() + "/token-jitsi").with(user(emailMedico)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roomName").value(s.room()))
				.andExpect(jsonPath("$.consultaId").exists());

		JWTClaimsSet c = claims(campo(r, "jwt"));
		assertThat(c.getStringClaim("room")).isEqualTo(s.room());
		assertThat(usuarioDe(c)).containsEntry("moderator", true).containsEntry("affiliation", "owner")
				.containsEntry("id", medico.getId().toString()).containsEntry("name", "Dra. Ana Lima")
				.doesNotContainKey("email");
	}

	@Test
	void soOMedicoDonoObtemTokenDeHost() throws Exception {
		Sala s = criar(true);
		String outro = "outro-" + UUID.randomUUID() + "@clinica.test";
		medicos.save(new Medico("Dr. Outro", "CRM-RJ 1", outro));

		mvc.perform(post("/api/agendamentos/" + s.id() + "/token-jitsi").with(user(outro))).andExpect(status().isNotFound());
		mvc.perform(post("/api/agendamentos/" + s.id() + "/token-jitsi")).andExpect(status().isUnauthorized());
	}

	@Test
	void pacienteTrocaOTokenDeEntradaPorJwtSemModeracaoESemAfiliacao() throws Exception {
		Sala s = criar(true);

		String entrada = tokenDeEntrada(s, CPF);
		admitir(s, entrada);
		ResultActions r = trocar(s.room(), entrada).andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("ADMITIDO"))
				.andExpect(jsonPath("$.roomName").value(s.room())).andExpect(jsonPath("$.consultaId").exists());

		JWTClaimsSet c = claims(campo(r, "jwt"));
		assertThat(c.getStringClaim("room")).isEqualTo(s.room());
		assertThat(usuarioDe(c)).containsEntry("moderator", false).containsEntry("name", "Maria Souza")
				.containsEntry("id", "paciente-" + s.id())
				.doesNotContainKey("affiliation").doesNotContainKey("email");
		assertThat(c.toString()).doesNotContain("529.982").doesNotContain("52998224725").doesNotContain("maria@mail.test");
	}

	@Test
	void comValidacaoDesativadaOPacienteTambemPassaPelaTroca() throws Exception {
		Sala s = criar(false);
		String entrada = campo(mvc.perform(post("/api/salas/" + s.room() + "/validar-cpf")).andExpect(status().isOk()), "tokenEntrada");
		consentir(s, entrada);

		admitir(s, entrada);
		trocar(s.room(), entrada).andExpect(status().isOk());
	}

	@Test
	void semPassarPelaPortaDoCpfNaoHaCredencial() throws Exception {
		Sala s = criar(true);

		trocar(s.room(), "lixo").andExpect(status().isForbidden());
		trocar(s.room(), "").andExpect(status().isForbidden());
		mvc.perform(post("/api/salas/" + s.room() + "/token")).andExpect(status().isForbidden());
		// O CPF errado nem chega a gerar token de entrada.
		mvc.perform(post("/api/salas/" + s.room() + "/validar-cpf").contentType(MediaType.APPLICATION_JSON)
				.content("{\"cpf\":\"111.444.777-35\"}")).andExpect(status().isForbidden());
	}

	@Test
	void tokenDeEntradaDeUmaSalaNaoAbreOutra() throws Exception {
		Sala a = criar(true);
		Sala b = criar(true);

		trocar(b.room(), tokenDeEntrada(a, CPF)).andExpect(status().isForbidden());
		trocar("consulta-nao-existe", tokenDeEntrada(a, CPF)).andExpect(status().isForbidden());
	}

	@Test
	void jwtDoMedicoNaoServeComoTokenDeEntrada() throws Exception {
		Sala s = criar(true);
		String jwtMedico = campo(mvc.perform(post("/api/agendamentos/" + s.id() + "/token-jitsi").with(user(emailMedico))), "jwt");

		trocar(s.room(), jwtMedico).andExpect(status().isForbidden());
	}

	@Test
	void emissoesSaoAuditadas() throws Exception {
		Sala s = criar(true);
		mvc.perform(post("/api/agendamentos/" + s.id() + "/token-jitsi").with(user(emailMedico)));
		String entrada = tokenDeEntrada(s, CPF);
		admitir(s, entrada);
		trocar(s.room(), entrada);

		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + s.id())).extracting("acao")
				.contains("TOKEN_JITSI_MEDICO", "TOKEN_JITSI_PACIENTE");
	}
}
