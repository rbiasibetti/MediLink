package br.com.medilink.admin;

import java.util.UUID;

/** Quem está autenticado na área administrativa. O e-mail vai para a auditoria como ator. */
public record AdminPrincipal(UUID id, String email) {
}
