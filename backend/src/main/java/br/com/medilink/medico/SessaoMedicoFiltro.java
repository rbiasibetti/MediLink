package br.com.medilink.medico;

import br.com.medilink.domain.AcessoMedicoLink.Modo;
import br.com.medilink.medico.SessaoMedicoService.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Autentica o médico que entrou por link (T17) com {@code Authorization: Bearer <sessão>} e RESTRINGE o que a sessão
 * alcança: só a consulta (e o agendamento) a que foi presa, e no modo LEITURA só a leitura. Qualquer outra rota é 403.
 * Token ausente, inválido ou vencido não autentica nada (as rotas protegidas respondem 401).
 */
@Component
public class SessaoMedicoFiltro extends OncePerRequestFilter {

	private static final Pattern CONSULTA = Pattern.compile("^/api/consultas/([0-9a-fA-F-]{36})(/[a-z]+)?$");
	private static final Pattern TOKEN_JITSI = Pattern.compile("^/api/agendamentos/([0-9a-fA-F-]{36})/token-jitsi$");
	private static final List<String> LEITURA = List.of("", "/transcricao");
	private static final List<String> ACOES = List.of("/aceite", "/encerrar", "/reabrir", "/participante");

	private final SessaoMedicoService sessoes;

	public SessaoMedicoFiltro(SessaoMedicoService sessoes) {
		this.sessoes = sessoes;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest r) {
		String p = r.getRequestURI();
		String h = r.getHeader("Authorization");
		return h == null || !h.startsWith("Bearer ") || p.startsWith("/api/integracao/") || p.startsWith("/api/admin/")
				|| p.startsWith("/api/audio-sink/") || p.startsWith("/api/salas/") || p.equals("/api/medico/sessao");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		String token = req.getHeader("Authorization").substring(7).strip();
		var claims = token.chars().filter(c -> c == '.').count() == 2 ? sessoes.verificar(token) : java.util.Optional.<Claims>empty();
		if (claims.isEmpty()) {
			chain.doFilter(req, res); // não é uma sessão do médico: segue sem autenticar
			return;
		}
		if (!permitido(req.getMethod(), req.getRequestURI(), claims.get())) {
			res.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		var auth = new UsernamePasswordAuthenticationToken(claims.get().medicoEmail(), null,
				List.of(new SimpleGrantedAuthority("ROLE_MEDICO_SESSAO")));
		SecurityContextHolder.getContext().setAuthentication(auth);
		chain.doFilter(req, res);
	}

	static boolean permitido(String metodo, String caminho, Claims c) {
		if (caminho.equals("/api/me")) {
			return metodo.equals("GET");
		}
		Matcher m = CONSULTA.matcher(caminho);
		if (m.matches()) {
			if (!idIgual(m.group(1), c.consultaId())) {
				return false;
			}
			String sub = m.group(2) == null ? "" : m.group(2);
			if (LEITURA.contains(sub)) {
				return metodo.equals("GET");
			}
			return c.modo() == Modo.SALA && ACOES.contains(sub) && metodo.equals("POST");
		}
		m = TOKEN_JITSI.matcher(caminho);
		return m.matches() && c.modo() == Modo.SALA && metodo.equals("POST") && idIgual(m.group(1), c.agendamentoId());
	}

	private static boolean idIgual(String texto, UUID esperado) {
		try {
			return UUID.fromString(texto).equals(esperado);
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}
}
