package br.com.medilink.integracao;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Autentica {@code /api/integracao/**} pelo cabeçalho {@code X-API-Key}. Só olha esse caminho: nas demais rotas o
 * cabeçalho é ignorado, e uma key não serve de login de médico nem de administrador. A resposta de falha é sempre a
 * mesma 401, sem dizer o motivo, e a key nunca vai para log.
 */
@Component
public class ApiKeyFiltro extends OncePerRequestFilter {

	public static final String CABECALHO = "X-API-Key";
	public static final String AUTORIDADE = "ROLE_INTEGRACAO";

	private final ApiKeyService keys;
	private final LimiteDeUso limite;

	public ApiKeyFiltro(ApiKeyService keys, LimiteDeUso limite) {
		this.keys = keys;
		this.limite = limite;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/integracao/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		var auth = keys.autenticar(req.getHeader(CABECALHO));
		if (auth.isEmpty()) {
			res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			res.setHeader("WWW-Authenticate", "ApiKey");
			return;
		}
		if (!limite.permitir(auth.get().keyId())) {
			res.setStatus(429);
			res.setHeader("Retry-After", "60");
			return;
		}
		List<SimpleGrantedAuthority> autoridades = new ArrayList<>();
		autoridades.add(new SimpleGrantedAuthority(AUTORIDADE));
		auth.get().escopos().forEach(e -> autoridades.add(new SimpleGrantedAuthority("SCOPE_" + e)));
		var token = new UsernamePasswordAuthenticationToken(auth.get(), null, autoridades);
		SecurityContextHolder.getContext().setAuthentication(token);
		chain.doFilter(req, res);
	}
}
