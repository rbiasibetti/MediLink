package br.com.medilink.admin;

import br.com.medilink.domain.Administrador;
import br.com.medilink.repository.AdministradorRepository;
import br.com.medilink.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Login do administrador (T18): senha + TOTP. Sem TOTP confirmado, o primeiro login (com a senha certa) devolve o
 * segredo para cadastrar no aplicativo e só entra quando o administrador prova com um código. Falha, de qualquer tipo,
 * é sempre o mesmo 403 genérico; 5 falhas bloqueiam por 15 min (429). Toda tentativa é auditada.
 */
@Service
public class AdminAuthService {

	public enum Etapa { OK, ENROLAR_TOTP }

	public record Resultado(Etapa etapa, String token, Instant expiraEm, String segredo, String otpauthUri) {
		@Override
		public String toString() {
			return "Resultado[etapa=" + etapa + "]";
		}
	}

	private static final String EMISSOR = "MediLink";
	private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
	/** Hash descartável para gastar o mesmo tempo quando o e-mail não existe (não revela quem é administrador). */
	private final String hashFalso = bcrypt.encode("nao-e-uma-senha-" + UUID.randomUUID());

	private final AdministradorRepository admins;
	private final AdminSessaoService sessoes;
	private final AdminProperties props;
	private final AuditService auditoria;
	private final Clock clock;

	public AdminAuthService(AdministradorRepository admins, AdminSessaoService sessoes, AdminProperties props, AuditService auditoria, Clock clock) {
		this.admins = admins;
		this.sessoes = sessoes;
		this.props = props;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	/** Falhas precisam ficar gravadas (contador e auditoria) mesmo lançando exceção. */
	@Transactional(noRollbackFor = ResponseStatusException.class)
	public Resultado entrar(String email, String senha, String codigo) {
		Instant agora = Instant.now(clock);
		Administrador a = admins.findByEmail(email == null ? "" : email.strip().toLowerCase()).filter(Administrador::isAtivo).orElse(null);
		boolean senhaOk = bcrypt.matches(senha == null ? "" : senha, a == null ? hashFalso : a.getSenhaHash()) && a != null;
		if (a != null && a.bloqueado(agora)) {
			auditoria.registrar(a.getEmail(), "ADMIN_LOGIN_BLOQUEADO", "admin/" + a.getId());
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos.");
		}
		if (!senhaOk) {
			falhar(a, agora);
		}
		if (!a.isTotpConfirmado()) {
			if (codigo == null || codigo.isBlank()) { // primeiro acesso: entrega o segredo para cadastrar no autenticador
				if (a.getTotpSegredo() == null) {
					a.definirSegredoTotp(Totp.novoSegredo());
				}
				auditoria.registrar(a.getEmail(), "ADMIN_TOTP_ENROLAMENTO", "admin/" + a.getId());
				return new Resultado(Etapa.ENROLAR_TOTP, null, null, a.getTotpSegredo(), Totp.uri(EMISSOR, a.getEmail(), a.getTotpSegredo()));
			}
			long passo = Totp.validar(a.getTotpSegredo(), codigo, Totp.passo(agora.getEpochSecond()), a.getTotpUltimoPasso());
			if (passo < 0) {
				falhar(a, agora);
			}
			a.confirmarTotp(passo);
			auditoria.registrar(a.getEmail(), "ADMIN_TOTP_CONFIRMADO", "admin/" + a.getId());
		}
		else {
			long passo = Totp.validar(a.getTotpSegredo(), codigo, Totp.passo(agora.getEpochSecond()), a.getTotpUltimoPasso());
			if (passo < 0) {
				falhar(a, agora);
			}
			a.usarPasso(passo);
		}
		a.registrarSucesso();
		auditoria.registrar(a.getEmail(), "ADMIN_LOGIN_OK", "admin/" + a.getId());
		var s = sessoes.emitir(a.getId());
		return new Resultado(Etapa.OK, s.token(), s.expiraEm(), null, null);
	}

	private void falhar(Administrador a, Instant agora) {
		if (a != null) {
			a.registrarFalha(agora, props.maxFalhas(), props.bloqueio());
			auditoria.registrar(a.getEmail(), "ADMIN_LOGIN_FALHA", "admin/" + a.getId());
		}
		else {
			auditoria.registrar("desconhecido", "ADMIN_LOGIN_FALHA", "admin/-");
		}
		throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Não foi possível autenticar.");
	}

	/** Cria um administrador (já ativo, sem TOTP: ele o cadastra no primeiro login). Política de senha mínima. */
	@Transactional
	public Administrador criar(String ator, String email, String senha) {
		String e = email == null ? "" : email.strip().toLowerCase();
		if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || e.length() > 254) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "E-mail inválido.");
		}
		if (senha == null || senha.length() < 12 || senha.length() > 128) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A senha precisa ter de 12 a 128 caracteres.");
		}
		if (admins.findByEmail(e).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um administrador com este e-mail.");
		}
		Administrador a = admins.save(new Administrador(e, bcrypt.encode(senha), Instant.now(clock)));
		auditoria.registrar(ator, "ADMIN_CRIADO", "admin/" + a.getId());
		return a;
	}

	@Transactional
	public void desativar(String ator, UUID alvo, UUID quemPede) {
		if (alvo.equals(quemPede)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Você não pode desativar o próprio acesso.");
		}
		Administrador a = admins.findById(alvo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		a.desativar();
		auditoria.registrar(ator, "ADMIN_DESATIVADO", "admin/" + a.getId());
	}

	public List<Administrador> listar() {
		return admins.findAll();
	}

	/** Cria o primeiro administrador a partir da configuração, se ainda não existir nenhum. */
	@Transactional
	public boolean criarPrimeiroSeNecessario() {
		if (props.bootstrapEmail().isBlank() || props.bootstrapSenha().isBlank() || admins.count() > 0) {
			return false;
		}
		criar("sistema", props.bootstrapEmail(), props.bootstrapSenha());
		return true;
	}
}
