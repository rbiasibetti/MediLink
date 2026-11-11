package br.com.medilink.web;

import br.com.medilink.CenarioDeConsulta;
import br.com.medilink.CenarioDeConsulta.Consulta;
import br.com.medilink.domain.AudioTransitorio;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.AuditLogRepository;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.transcricao.PublicadorDeJobs;
import br.com.medilink.transcricao.ResultadoDaTranscricaoService;
import br.com.medilink.transcricao.TranscricaoJobService;
import br.com.medilink.transcricao.TranscricaoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code medilink.transcricao.leitura-pelo-medico}: o médico só lê a transcrição pela plataforma com o parâmetro ligado,
 * e o texto é decifrado a cada requisição (no banco fica sempre cifrado).
 */
class LeituraDeTranscricaoPeloMedicoTest {

	@Test
	void desligadaPorPadraoParaNaoAbrirAcessoSemQueOAdministradorDecida() {
		var props = new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("medilink.transcricao", TranscricaoProperties.class);

		assertThat(props.leituraPeloMedico()).isFalse();
	}

	abstract static class Base {
		@Autowired MockMvc mvc;
		@Autowired MedicoRepository medicos;
		@Autowired TranscricaoJobService jobsService;
		@Autowired ResultadoDaTranscricaoService resultados;
		@Autowired TranscricaoJobRepository jobs;
		@Autowired AudioTransitorioRepository audios;
		@Autowired AuditLogRepository auditoria;
		@Autowired JdbcTemplate jdbc;
		@MockitoBean PublicadorDeJobs publicador;

		CenarioDeConsulta cenario;
		Consulta c;

		@BeforeEach
		void montar() throws Exception {
			cenario = new CenarioDeConsulta(mvc, medicos);
			c = cenario.criar();
			UUID id = UUID.fromString(c.id());
			cenario.consentir(c, true);
			cenario.iniciar(c);
			cenario.encerrar(c);
			AudioTransitorio a = new AudioTransitorio(id, "med-a0", "med00001", c.id() + "/med00001-1.opus", Instant.now().minusSeconds(60));
			a.completar(Instant.now(), 960);
			audios.save(a);
			jobsService.publicarPendentes();
			resultados.processar("""
					{"versao":1,"jobId":"%s","consultaId":"%s","status":"CONCLUIDO","idioma":"pt","duracaoMs":9000,
					 "segmentos":[{"papel":"MEDICO","inicioMs":100,"fimMs":2000,"texto":"Sigilo médico."}]}
					""".formatted(jobs.findByConsultaId(id).orElseThrow().getId(), id));
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = "medilink.transcricao.leitura-pelo-medico=true")
	class Habilitada extends Base {

		@Test
		void medicoLeOTextoDecifradoNaRequisicaoEOBancoContinuaCifrado() throws Exception {
			assertThat(jdbc.queryForObject("SELECT texto FROM transcricao WHERE consulta_id = ?", String.class, UUID.fromString(c.id())))
					.startsWith("enc:").doesNotContain("Sigilo");

			mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.texto").value("Médico: Sigilo médico."))
					.andExpect(jsonPath("$.segmentos[0].texto").value("Sigilo médico."));
			assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao").contains("TRANSCRICAO_LIDA");
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = "medilink.transcricao.leitura-pelo-medico=false")
	class Desabilitada extends Base {

		@Test
		void medicoDonoRecebe403SemTextoEATentativaFicaAuditada() throws Exception {
			mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(cenario.emailMedico)))
					.andExpect(status().isForbidden())
					.andExpect(content().string(not(containsString("Sigilo"))))
					.andExpect(jsonPath("$.detail").value(containsString("desabilitada")));

			assertThat(auditoria.findByRecursoOrderByMomento("consulta/" + c.id())).extracting("acao")
					.contains("TRANSCRICAO_LEITURA_DESABILITADA").doesNotContain("TRANSCRICAO_LIDA");
		}

		@Test
		void consultaDeOutroMedicoSeguePareceNaoExistir() throws Exception {
			var outro = new CenarioDeConsulta(mvc, medicos);

			mvc.perform(get("/api/consultas/" + c.id() + "/transcricao").with(user(outro.emailMedico))).andExpect(status().isNotFound());
		}

		@Test
		void listaContinuaMostrandoASituacaoMasAvisaQueALeituraEstaDesligada() throws Exception {
			mvc.perform(get("/api/consultas").with(user(cenario.emailMedico)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.leituraDeTranscricao").value(false))
					.andExpect(jsonPath("$.itens[0].transcricao").value("DISPONIVEL"))
					.andExpect(content().string(not(containsString("Sigilo"))));
		}
	}
}
