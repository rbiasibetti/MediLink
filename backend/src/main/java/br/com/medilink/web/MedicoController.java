package br.com.medilink.web;

import br.com.medilink.repository.MedicoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/** Quem sou eu: o Angular usa para validar o login do médico e mostrar o nome. */
@RestController
@RequestMapping("/api/me")
class MedicoController {

	record Eu(String nome, String email) {
	}

	private final MedicoRepository medicos;

	MedicoController(MedicoRepository medicos) {
		this.medicos = medicos;
	}

	@GetMapping
	Eu eu(Principal principal) {
		return medicos.findByEmail(principal.getName()).map(m -> new Eu(m.getNome(), m.getEmail()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
	}
}
