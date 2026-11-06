package br.com.medilink.medico;

import br.com.medilink.repository.MedicoRepository;
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

/**
 * Autentica o médico que entrou com usuário e senha (T20) por {@code Authorization: Bearer <sessão>}. Confere a cada
 * requisição que o médico continua ativo: desativá-lo corta o acesso na hora. Token que não é desta sessão não
 * autentica nada (outros filtros tratam os seus; as rotas protegidas respondem 401).
 */
@Component
public class LoginMedicoFiltro extends OncePerRequestFilter {

	private final LoginMedicoSessaoService sessoes;
	private final MedicoRepository medicos;

	public LoginMedicoFiltro(LoginMedicoSessaoService sessoes, MedicoRepository medicos) {
		this.sessoes = sessoes;
		this.medicos = medicos;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest r) {
		String p = r.getRequestURI();
		String h = r.getHeader("Authorization");
		return h == null || !h.startsWith("Bearer ") || p.startsWith("/api/integracao/") || p.startsWith("/api/admin/")
				|| p.startsWith("/api/audio-sink/") || p.startsWith("/api/salas/") || p.equals("/api/medico/sessao") || p.equals("/api/medico/login");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		String token = req.getHeader("Authorization").substring(7).strip();
		if (token.chars().filter(c -> c == '.').count() == 2) {
			var medico = sessoes.verificar(token).flatMap(medicos::findById).filter(m -> m.isAtivo());
			if (medico.isPresent()) {
				var auth = new UsernamePasswordAuthenticationToken(medico.get().getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_MEDICO")));
				SecurityContextHolder.getContext().setAuthentication(auth);
			}
		}
		chain.doFilter(req, res);
	}
}
