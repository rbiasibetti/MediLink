package br.com.medilink.medico;

import br.com.medilink.admin.Totp;
import br.com.medilink.domain.Medico;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Login do médico (T20): e-mail + senha, mais o código TOTP quando o administrador o habilitou para aquele médico.
 * Com o TOTP habilitado e ainda não confirmado, o primeiro login (com a senha certa) devolve o segredo para cadastrar
 * no aplicativo e só entra quando o médico prova com um código. Falha, de qualquer tipo, é sempre o mesmo 403
 * genérico (não revela se o e-mail existe nem se o TOTP está ligado); 5 falhas bloqueiam por 15 min (429).
 */
@Service
public class LoginMedicoService {

	public enum Etapa { OK, ENROLAR_TOTP }

	public record Resultado(Etapa etapa, String token, Instant expiraEm, String nome, String segredo, String otpauthUri) {
		@Override
		public String toString() {
			return "Resultado[etapa=" + etapa + "]";
		}
	}

	private static final String EMISSOR = "MediLink";
	private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
	/** Hash descartável para gastar o mesmo tempo quando o e-mail não existe. */
	private final String hashFalso = bcrypt.encode("nao-e-uma-senha-" + UUID.randomUUID());

	private final MedicoRepository medicos;
	private final LoginMedicoSessaoService sessoes;
	private final LoginMedicoProperties props;
	private final AuditService auditoria;
	private final Clock clock;

	public LoginMedicoService(MedicoRepository medicos, LoginMedicoSessaoService sessoes, LoginMedicoProperties props, AuditService auditoria, Clock clock) {
		this.medicos = medicos;
		this.sessoes = sessoes;
		this.props = props;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	/** Falhas precisam ficar gravadas (contador e auditoria) mesmo lançando exceção. */
	@Transactional(noRollbackFor = ResponseStatusException.class)
	public Resultado entrar(String email, String senha, String codigo) {
		Instant agora = Instant.now(clock);
		Medico m = medicos.findByEmail(email == null ? "" : email.strip().toLowerCase())
				.filter(x -> x.isAtivo() && x.getSenhaHash() != null).orElse(null);
		boolean senhaOk = bcrypt.matches(senha == null ? "" : senha, m == null ? hashFalso : m.getSenhaHash()) && m != null;
		if (m != null && m.bloqueado(agora)) {
			auditoria.registrar(m.getEmail(), "MEDICO_LOGIN_BLOQUEADO", "medico/" + m.getId());
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos.");
		}
		if (!senhaOk) {
			falhar(m, agora);
		}
		if (m.isTotpHabilitado()) {
			if (!m.isTotpConfirmado()) {
				if (codigo == null || codigo.isBlank()) { // primeiro acesso com TOTP: entrega o segredo para cadastrar no autenticador
					if (m.getTotpSegredo() == null) {
						m.definirSegredoTotp(Totp.novoSegredo());
					}
					auditoria.registrar(m.getEmail(), "MEDICO_TOTP_ENROLAMENTO", "medico/" + m.getId());
					return new Resultado(Etapa.ENROLAR_TOTP, null, null, null, m.getTotpSegredo(), Totp.uri(EMISSOR, m.getEmail(), m.getTotpSegredo()));
				}
				long passo = Totp.validar(m.getTotpSegredo(), codigo, Totp.passo(agora.getEpochSecond()), m.getTotpUltimoPasso());
				if (passo < 0) {
					falhar(m, agora);
				}
				m.confirmarTotp(passo);
				auditoria.registrar(m.getEmail(), "MEDICO_TOTP_CONFIRMADO", "medico/" + m.getId());
			}
			else {
				long passo = Totp.validar(m.getTotpSegredo(), codigo, Totp.passo(agora.getEpochSecond()), m.getTotpUltimoPasso());
				if (passo < 0) {
					falhar(m, agora);
				}
				m.usarPasso(passo);
			}
		}
		m.registrarSucesso();
		auditoria.registrar(m.getEmail(), "MEDICO_LOGIN_OK", "medico/" + m.getId());
		var s = sessoes.emitir(m.getId());
		return new Resultado(Etapa.OK, s.token(), s.expiraEm(), m.getNome(), null, null);
	}

	private void falhar(Medico m, Instant agora) {
		if (m != null) {
			m.registrarFalha(agora, props.maxFalhas(), props.bloqueio());
			auditoria.registrar(m.getEmail(), "MEDICO_LOGIN_FALHA", "medico/" + m.getId());
		}
		else {
			auditoria.registrar("desconhecido", "MEDICO_LOGIN_FALHA", "medico/-");
		}
		throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível autenticar.");
	}
}
