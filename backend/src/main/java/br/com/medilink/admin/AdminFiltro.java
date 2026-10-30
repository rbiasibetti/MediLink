package br.com.medilink.admin;

import br.com.medilink.repository.AdministradorRepository;
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
 * Autentica {@code /api/admin/**} (menos o login) com a sessão do administrador. Confere a cada requisição que o
 * administrador continua ativo: desativar alguém corta o acesso na hora, sem esperar a sessão vencer.
 */
@Component
public class AdminFiltro extends OncePerRequestFilter {

	private final AdminSessaoService sessoes;
	private final AdministradorRepository admins;

	public AdminFiltro(AdminSessaoService sessoes, AdministradorRepository admins) {
		this.sessoes = sessoes;
		this.admins = admins;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest r) {
		String h = r.getHeader("Authorization");
		return !r.getRequestURI().startsWith("/api/admin/") || r.getRequestURI().equals("/api/admin/login") || h == null || !h.startsWith("Bearer ");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
		String token = req.getHeader("Authorization").substring(7).strip();
		var id = sessoes.verificar(token);
		var admin = id.flatMap(admins::findById).filter(a -> a.isAtivo());
		if (admin.isPresent()) {
			var auth = new UsernamePasswordAuthenticationToken(new AdminPrincipal(admin.get().getId(), admin.get().getEmail()), null,
					List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
			SecurityContextHolder.getContext().setAuthentication(auth);
		}
		chain.doFilter(req, res);
	}
}
