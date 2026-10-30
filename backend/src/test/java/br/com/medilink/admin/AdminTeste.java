package br.com.medilink.admin;

import br.com.medilink.repository.AdministradorRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Entra como administrador de verdade (senha + TOTP) para os testes da área administrativa. */
final class AdminTeste {

	static final String SENHA = "uma-senha-bem-longa-123";

	private AdminTeste() {
	}

	static String token(MockMvc mvc, AdminAuthService auth) throws Exception {
		String email = "admin-" + UUID.randomUUID() + "@clinica.test";
		auth.criar("teste", email, SENHA);
		String segredo = campo(login(mvc, email, null), "segredoTotp");
		return campo(login(mvc, email, Totp.gerar(segredo, Totp.passo(Instant.now().getEpochSecond()))), "token");
	}

	private static ResultActions login(MockMvc mvc, String email, String codigo) throws Exception {
		String cod = codigo == null ? "" : ",\"codigo\":\"" + codigo + "\"";
		return mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"senha\":\"" + SENHA + "\"" + cod + "}"));
	}

	static String campo(ResultActions r, String nome) throws Exception {
		Matcher m = Pattern.compile("\"" + nome + "\":\"([^\"]+)\"").matcher(r.andReturn().getResponse().getContentAsString());
		assertThat(m.find()).as("%s em %s", nome, r.andReturn().getResponse().getContentAsString()).isTrue();
		return m.group(1);
	}

	static String bearer(String token) {
		return "Bearer " + token;
	}
}
