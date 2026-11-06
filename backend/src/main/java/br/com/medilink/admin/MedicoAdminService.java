package br.com.medilink.admin;

import br.com.medilink.domain.Medico;
import br.com.medilink.repository.MedicoRepository;
import br.com.medilink.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Cadastro e manutenção de médicos pelo administrador (T20), inclusive a decisão de exigir TOTP no login de cada um. */
@Service
public class MedicoAdminService {

	private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
	private final MedicoRepository medicos;
	private final AuditService auditoria;

	public MedicoAdminService(MedicoRepository medicos, AuditService auditoria) {
		this.medicos = medicos;
		this.auditoria = auditoria;
	}

	public List<Medico> listar() {
		return medicos.findAll(org.springframework.data.domain.Sort.by("nome"));
	}

	@Transactional
	public Medico criar(String ator, String nome, String identificadorProfissional, String email, String senha, boolean exigirTotp) {
		String e = email == null ? "" : email.strip().toLowerCase();
		if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || e.length() > 254) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "E-mail inválido.");
		}
		validarSenha(senha);
		if (medicos.findByEmail(e).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe um médico com este e-mail.");
		}
		Medico m = new Medico(nome.strip(), identificadorProfissional.strip(), e);
		m.definirSenha(bcrypt.encode(senha));
		if (exigirTotp) {
			m.habilitarTotp();
		}
		medicos.save(m);
		auditoria.registrar(ator, "MEDICO_CRIADO", "medico/" + m.getId());
		return m;
	}

	@Transactional
	public Medico atualizar(String ator, UUID id, String nome, String identificadorProfissional) {
		Medico m = buscar(id);
		m.atualizar(nome.strip(), identificadorProfissional.strip());
		auditoria.registrar(ator, "MEDICO_ALTERADO", "medico/" + id);
		return m;
	}

	@Transactional
	public Medico definirSenha(String ator, UUID id, String senha) {
		validarSenha(senha);
		Medico m = buscar(id);
		m.definirSenha(bcrypt.encode(senha));
		auditoria.registrar(ator, "MEDICO_SENHA_REDEFINIDA", "medico/" + id);
		return m;
	}

	@Transactional
	public Medico ativar(String ator, UUID id, boolean ativo) {
		Medico m = buscar(id);
		if (ativo) {
			m.ativar();
		}
		else {
			m.desativar();
		}
		auditoria.registrar(ator, ativo ? "MEDICO_ATIVADO" : "MEDICO_DESATIVADO", "medico/" + id);
		return m;
	}

	/**
	 * Liga ou desliga o TOTP do médico. Ligar de novo com ele já ligado zera o segredo: serve para trocar de celular
	 * (o médico cadastra um segredo novo no próximo login).
	 */
	@Transactional
	public Medico totp(String ator, UUID id, boolean habilitar) {
		Medico m = buscar(id);
		if (habilitar) {
			m.habilitarTotp();
		}
		else {
			m.desabilitarTotp();
		}
		auditoria.registrar(ator, habilitar ? "MEDICO_TOTP_HABILITADO" : "MEDICO_TOTP_DESABILITADO", "medico/" + id);
		return m;
	}

	private Medico buscar(UUID id) {
		return medicos.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private static void validarSenha(String senha) {
		if (senha == null || senha.length() < 12 || senha.length() > 128) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A senha precisa ter de 12 a 128 caracteres.");
		}
	}
}
