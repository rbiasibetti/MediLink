package br.com.medilink.integracao;

import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.IntegradorRepository;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T16: sistemas externos criam, alteram, consultam e cancelam reuniões com a API key. */
@SpringBootTest
@AutoConfigureMockMvc
class IntegracaoApiTest {

	private static final String CPF = "529.982.247-25";
	private static final String BASE = "/api/integracao/v1/agendamentos";

	@Autowired MockMvc mvc;
	@Autowired IntegradorRepository integradores;
	@Autowired ApiKeyService keys;
	@Autowired AgendamentoRepository agendamentos;
	@Autowired MedicoRepository medicos;
	@Autowired AuditLogRepository auditoria;
	@Autowired JdbcTemplate jdbc;
	@org.springframework.test.context.bean.override.mockito.MockitoBean org.springframework.mail.javamail.JavaMailSender mail;

	private SistemaIntegrado sistema;

	@BeforeEach
	void montar() {
		sistema = new SistemaIntegrado(integradores, keys);
		org.mockito.Mockito.when(mail.createMimeMessage()).thenAnswer(i -> new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties())));
	}

	private static String amanha() {
		return Instant.now().plus(1, ChronoUnit.DAYS).atZone(ZoneId.of("-03:00")).toOffsetDateTime().toString();
	}

	private String corpo(String ref, String extra) {
		return """
				{"medico":{"nome":"Dra. Ana Lima","chave":"CRM-SP-123456"},
				 "agendamento":{"chave":"%s","quando":{"dataHora":"%s"}},
				 "paciente":{"nome":"Maria Souza","cpf":"%s","email":"maria@mail.test"}%s}
				""".formatted(ref, amanha(), CPF, extra);
	}

	private ResultActions criar(String ref, String extra) throws Exception {
		return mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(corpo(ref, extra)));
	}

	private static String campo(ResultActions r, String nome) throws Exception {
		String json = r.andReturn().getResponse().getContentAsString();
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(json);
		assertThat(m.find()).as("%s em %s", nome, json).isTrue();
		return m.group(1);
	}

	@Test
	void criaEDevolveAChaveDaReuniaoEOLinkDoPaciente() throws Exception {
		ResultActions r = criar("ext-1", "").andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("AGENDADO"))
				.andExpect(jsonPath("$.referencia").value("ext-1"))
				.andExpect(jsonPath("$.cpfObrigatorio").value(true))
				.andExpect(jsonPath("$.linkPaciente").value(containsString("/sala/consulta-")))
				.andExpect(content().string(not(containsString("529.982")))).andExpect(content().string(not(containsString("maria@mail"))));
		UUID chave = UUID.fromString(campo(r, "chaveReuniao"));

		var ag = agendamentos.findById(chave).orElseThrow();
		assertThat(ag.getIntegradorId()).isEqualTo(sistema.integrador.getId());
		assertThat(chave.toString()).doesNotContain(ag.getRoomName()); // a chave não é o segredo do link
		assertThat(r.andReturn().getResponse().getContentAsString()).doesNotContain(ag.getRoomName().replace("consulta-", "") + "x");
		var medico = medicos.findByIntegradorIdAndChaveExterna(sistema.integrador.getId(), "CRM-SP-123456").orElseThrow();
		assertThat(medico.getNome()).isEqualTo("Dra. Ana Lima");
		assertThat(medico.getEmail()).endsWith("@integracao.medilink.invalid");
		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + chave)).extracting("acao", "ator")
				.contains(org.assertj.core.groups.Tuple.tuple("AGENDAMENTO_CRIADO", "api-key/" + sistema.key.keyId()));
	}

	@Test
	void nomeEEmailDoPacienteFicamCifradosNoBanco() throws Exception {
		UUID chave = UUID.fromString(campo(criar("ext-cifra", "").andExpect(status().isCreated()), "chaveReuniao"));

		var linha = jdbc.queryForMap("SELECT paciente_nome, paciente_email, paciente_cpf_hmac FROM agendamento WHERE id = ?", chave);
		assertThat((String) linha.get("paciente_nome")).startsWith("enc:").doesNotContain("Maria");
		assertThat((String) linha.get("paciente_email")).startsWith("enc:").doesNotContain("maria@mail");
		assertThat((String) linha.get("paciente_cpf_hmac")).doesNotContain("52998224725").hasSize(64);
		assertThat(agendamentos.findById(chave).orElseThrow().getPacienteNome()).isEqualTo("Maria Souza");
	}

	@Test
	void criarDeNovoComAMesmaReferenciaEIdempotente() throws Exception {
		String primeira = campo(criar("ext-idem", "").andExpect(status().isCreated()), "chaveReuniao");

		String segunda = campo(criar("ext-idem", "").andExpect(status().isOk()), "chaveReuniao");

		assertThat(segunda).isEqualTo(primeira);
	}

	@Test
	void semCpfAValidacaoFicaDesligada() throws Exception {
		String json = """
				{"medico":{"nome":"Dr. Rui","chave":"M1"},"agendamento":{"chave":"sem-cpf","quando":{"dataHora":"%s"}},
				 "paciente":{"nome":"Joao"}}""".formatted(amanha());

		mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.cpfObrigatorio").value(false));
	}

	@Test
	void dataEHorarioSeparadosComFusoPadraoDeSaoPaulo() throws Exception {
		LocalDate dia = LocalDate.now().plusDays(3);
		String json = """
				{"medico":{"nome":"Dr. Rui","chave":"M1"},"agendamento":{"chave":"data-hora","quando":{"data":"%s","horario":"14:30"}},
				 "paciente":{"nome":"Joao"}}""".formatted(dia);

		ResultActions r = mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated());

		assertThat(agendamentos.findById(UUID.fromString(campo(r, "chaveReuniao"))).orElseThrow().getDataHora())
				.isEqualTo(dia.atTime(14, 30).atZone(ZoneId.of("America/Sao_Paulo")).toInstant());
	}

	@Test
	void rejeitaDadosInvalidos() throws Exception {
		String passado = Instant.now().minus(1, ChronoUnit.DAYS).atZone(ZoneId.of("UTC")).toOffsetDateTime().toString();
		String longe = Instant.now().plus(800, ChronoUnit.DAYS).atZone(ZoneId.of("UTC")).toOffsetDateTime().toString();
		for (String quando : new String[] {"{\"dataHora\":\"" + passado + "\"}", "{\"dataHora\":\"" + longe + "\"}",
				"{\"data\":\"2030-01-01\"}", "{\"dataHora\":\"" + amanha() + "\",\"data\":\"2030-01-01\",\"horario\":\"10:00\"}",
				"{\"data\":\"2030-01-01\",\"horario\":\"10:00\",\"fuso\":\"Marte/Olimpo\"}"}) {
			String json = "{\"medico\":{\"nome\":\"Dr\",\"chave\":\"M1\"},\"agendamento\":{\"chave\":\"inv-" + quando.hashCode() + "\",\"quando\":" + quando
					+ "},\"paciente\":{\"nome\":\"Joao\"}}";
			mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(json))
					.andExpect(status().isUnprocessableEntity());
		}
		criar("cpf-ruim", "").andExpect(status().isCreated());
		mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content(corpo("cpf-ruim-2", "").replace(CPF, "111.111.111-11"))).andExpect(status().isUnprocessableEntity());
		mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content(corpo("email-ruim", "").replace("maria@mail.test", "nao-e-email"))).andExpect(status().isBadRequest());
		mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content(corpo("chave ruim!", ""))).andExpect(status().isBadRequest());
	}

	@Test
	void altera_soEnquantoAConsultaNaoComecou_eOLinkNaoMuda() throws Exception {
		String chave = campo(criar("ext-edit", "").andExpect(status().isCreated()), "chaveReuniao");
		String link = campo(mvc.perform(get(BASE + "/" + chave).header("X-API-Key", sistema.chave())), "linkPaciente");
		String novaData = Instant.now().plus(5, ChronoUnit.DAYS).atZone(ZoneId.of("UTC")).toOffsetDateTime().toString();

		mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"quando\":{\"dataHora\":\"" + novaData + "\"},\"paciente\":{\"nome\":\"Maria S. Souza\",\"cpf\":\"\"}}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.linkPaciente").value(link)).andExpect(jsonPath("$.cpfObrigatorio").value(false));

		var ag = agendamentos.findById(UUID.fromString(chave)).orElseThrow();
		assertThat(ag.getPacienteNome()).isEqualTo("Maria S. Souza");
		assertThat(ag.getPacienteCpfHmac()).isNull();
		assertThat(ag.getDataHora()).isEqualTo(Instant.parse(novaData.replace("+00:00", "Z")).truncatedTo(ChronoUnit.MILLIS));

		jdbc.update("UPDATE consulta SET status = 'EM_ANDAMENTO' WHERE agendamento_id = ?", UUID.fromString(chave));
		mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"paciente\":{\"nome\":\"Outro\"}}")).andExpect(status().isConflict());
	}

	@Test
	void naoTrocaDeMedico() throws Exception {
		String chave = campo(criar("ext-med", "").andExpect(status().isCreated()), "chaveReuniao");

		mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"medico\":{\"nome\":\"Dr. X\",\"chave\":\"OUTRO\"}}")).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void cancelaAntesDeComecarEIdempotenteEOLinkDeixaDeFuncionar() throws Exception {
		var r = criar("ext-del", "").andExpect(status().isCreated());
		String chave = campo(r, "chaveReuniao");
		String sala = campo(r, "linkPaciente").replaceAll(".*/sala/", "");
		mvc.perform(get("/api/salas/" + sala + "/acesso")).andExpect(status().isOk());

		mvc.perform(delete(BASE + "/" + chave).header("X-API-Key", sistema.chave())).andExpect(status().isNoContent());
		mvc.perform(delete(BASE + "/" + chave).header("X-API-Key", sistema.chave())).andExpect(status().isNoContent());

		mvc.perform(get("/api/salas/" + sala + "/acesso")).andExpect(status().isNotFound());
		mvc.perform(get(BASE + "/" + chave).header("X-API-Key", sistema.chave())).andExpect(jsonPath("$.status").value("CANCELADO"));
		mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"paciente\":{\"nome\":\"X\"}}")).andExpect(status().isConflict());
	}

	@Test
	void naoExcluiReuniaoJaIniciada() throws Exception {
		String chave = campo(criar("ext-iniciada", "").andExpect(status().isCreated()), "chaveReuniao");
		jdbc.update("UPDATE consulta SET status = 'EM_ANDAMENTO' WHERE agendamento_id = ?", UUID.fromString(chave));

		mvc.perform(delete(BASE + "/" + chave).header("X-API-Key", sistema.chave())).andExpect(status().isConflict());
		assertThat(agendamentos.findById(UUID.fromString(chave)).orElseThrow().getStatus().name()).isEqualTo("AGENDADO");
	}

	@Test
	void umSistemaNaoEnxergaNemMexeNaReuniaoDeOutro() throws Exception {
		String chave = campo(criar("ext-isol", "").andExpect(status().isCreated()), "chaveReuniao");
		var outro = new SistemaIntegrado(integradores, keys);

		mvc.perform(get(BASE + "/" + chave).header("X-API-Key", outro.chave())).andExpect(status().isNotFound());
		mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", outro.chave()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"paciente\":{\"nome\":\"X\"}}")).andExpect(status().isNotFound());
		mvc.perform(delete(BASE + "/" + chave).header("X-API-Key", outro.chave())).andExpect(status().isNotFound());
		// A mesma referência em outro sistema é outra reunião.
		mvc.perform(post(BASE).header("X-API-Key", outro.chave()).contentType(MediaType.APPLICATION_JSON).content(corpo("ext-isol", "")))
				.andExpect(status().isCreated());
		// E a mesma chave de médico em outro sistema é outro médico.
		assertThat(medicos.findByIntegradorIdAndChaveExterna(outro.integrador.getId(), "CRM-SP-123456")).isPresent();
	}

	@Test
	void notificarEnviaOLinkPorEmailSoSeHouverEmailEDizSeEnviouDeVerdade() throws Exception {
		criar("ext-mail", ",\"notificar\":true").andExpect(status().isCreated())
				.andExpect(jsonPath("$.notificacao.solicitada").value(true)).andExpect(jsonPath("$.notificacao.enviada").value(true));
		var msg = org.mockito.ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
		org.mockito.Mockito.verify(mail).send(msg.capture());
		assertThat(msg.getValue().getContent().toString()).contains("/sala/consulta-").contains("Olá, Maria Souza");
		assertThat(msg.getValue().getAllRecipients()[0].toString()).isEqualTo("maria@mail.test");

		// Sem e-mail do paciente: não há o que enviar.
		String semEmail = """
				{"medico":{"nome":"Dr","chave":"M1"},"agendamento":{"chave":"ext-mail2","quando":{"dataHora":"%s"}},
				 "paciente":{"nome":"Joao"},"notificar":true}""".formatted(amanha());
		mvc.perform(post(BASE).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON).content(semEmail))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.notificacao.solicitada").value(false)).andExpect(jsonPath("$.notificacao.enviada").value(false));
		org.mockito.Mockito.verify(mail, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class)); // só o primeiro enviou
	}

	@Test
	void falhaDoRelayNaoDerrubaACriacaoEAResposta_naoDizQueEnviou() throws Exception {
		org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("550 maria@mail.test rejeitado")).when(mail).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));

		String r = criar("ext-mail-falha", ",\"notificar\":true").andExpect(status().isCreated())
				.andExpect(jsonPath("$.notificacao.solicitada").value(true)).andExpect(jsonPath("$.notificacao.enviada").value(false))
				.andReturn().getResponse().getContentAsString();

		assertThat(r).doesNotContain("550").doesNotContain("rejeitado");
		UUID chave = UUID.fromString(r.replaceAll(".*\"chaveReuniao\":\"([^\"]+)\".*", "$1"));
		assertThat(auditoria.findByRecursoOrderByMomento("agendamento/" + chave)).extracting("acao").contains("AGENDAMENTO_CRIADO", "EMAIL_LINK_FALHA");
	}

	@Test
	void naoEnviaMaisDeUmEmailPorMinutoParaAMesmaReuniao() throws Exception {
		String chave = campo(criar("ext-mail-3", ",\"notificar\":true").andExpect(status().isCreated()), "chaveReuniao");

		for (int i = 0; i < 3; i++) {
			mvc.perform(patch(BASE + "/" + chave).header("X-API-Key", sistema.chave()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"notificar\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.notificacao.enviada").value(false));
		}
		org.mockito.Mockito.verify(mail, org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
	}

	@Test
	void respostaDeConsultaNaoTrazDadoDoPaciente() throws Exception {
		String chave = campo(criar("ext-get", "").andExpect(status().isCreated()), "chaveReuniao");

		mvc.perform(get(BASE + "/" + chave).header("X-API-Key", sistema.chave()))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString("Maria")))).andExpect(content().string(not(containsString("529"))))
				.andExpect(content().string(not(containsString("@mail"))));
	}

	@Test
	void keyPrecisaDoEscopoCerto() throws Exception {
		var soLeitura = new SistemaIntegrado(integradores, keys, Set.of("agendamento:ler"));

		mvc.perform(post(BASE).header("X-API-Key", soLeitura.chave()).contentType(MediaType.APPLICATION_JSON).content(corpo("x", "")))
				.andExpect(status().isForbidden());
		mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("X-API-Key", soLeitura.chave())).andExpect(status().isNotFound());
	}
}
