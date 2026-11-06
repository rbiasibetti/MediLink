package br.com.medilink.admin;

import br.com.medilink.domain.Medico;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Cadastro de médicos (T20). Exige a sessão do administrador. Nenhuma resposta traz senha, hash nem segredo TOTP. */
@RestController
@RequestMapping("/api/admin/medicos")
class MedicoAdminController {

	record NovoMedico(@NotBlank @Size(max = 200) String nome, @NotBlank @Size(max = 50) String identificadorProfissional,
			@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String senha, Boolean exigirTotp) {
		@Override
		public String toString() {
			return "NovoMedico[]";
		}
	}

	record AlterarMedico(@NotBlank @Size(max = 200) String nome, @NotBlank @Size(max = 50) String identificadorProfissional) {
	}

	record NovaSenha(@NotBlank @Size(max = 128) String senha) {
		@Override
		public String toString() {
			return "NovaSenha[]";
		}
	}

	record MedicoResposta(UUID id, String nome, String identificadorProfissional, String email, boolean ativo, boolean temSenha,
			boolean totpHabilitado, boolean totpConfirmado, boolean deIntegracao) {
		static MedicoResposta de(Medico m) {
			return new MedicoResposta(m.getId(), m.getNome(), m.getIdentificadorProfissional(), m.getEmail(), m.isAtivo(),
					m.getSenhaHash() != null, m.isTotpHabilitado(), m.isTotpConfirmado(), m.getIntegradorId() != null);
		}
	}

	private final MedicoAdminService medicos;

	MedicoAdminController(MedicoAdminService medicos) {
		this.medicos = medicos;
	}

	@GetMapping
	List<MedicoResposta> listar() {
		return medicos.listar().stream().map(MedicoResposta::de).toList();
	}

	@PostMapping
	ResponseEntity<MedicoResposta> criar(@Valid @RequestBody NovoMedico req, @AuthenticationPrincipal AdminPrincipal eu) {
		Medico m = medicos.criar(eu.email(), req.nome(), req.identificadorProfissional(), req.email(), req.senha(), Boolean.TRUE.equals(req.exigirTotp()));
		return ResponseEntity.status(HttpStatus.CREATED).body(MedicoResposta.de(m));
	}

	@PutMapping("/{id}")
	MedicoResposta alterar(@PathVariable UUID id, @Valid @RequestBody AlterarMedico req, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.atualizar(eu.email(), id, req.nome(), req.identificadorProfissional()));
	}

	@PostMapping("/{id}/senha")
	MedicoResposta senha(@PathVariable UUID id, @Valid @RequestBody NovaSenha req, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.definirSenha(eu.email(), id, req.senha()));
	}

	@PostMapping("/{id}/ativar")
	MedicoResposta ativar(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.ativar(eu.email(), id, true));
	}

	@PostMapping("/{id}/desativar")
	MedicoResposta desativar(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.ativar(eu.email(), id, false));
	}

	@PostMapping("/{id}/totp/habilitar")
	MedicoResposta habilitarTotp(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.totp(eu.email(), id, true));
	}

	@PostMapping("/{id}/totp/desabilitar")
	MedicoResposta desabilitarTotp(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		return MedicoResposta.de(medicos.totp(eu.email(), id, false));
	}
}
