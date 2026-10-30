package br.com.medilink.admin;

import br.com.medilink.domain.TermoVersao;
import br.com.medilink.domain.TermoVersao.Status;
import br.com.medilink.repository.TermoVersaoRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.service.TermoConsentimento;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Gestão do termo de consentimento pelo administrador (T18). Rascunho edita; publicar torna vigente (e arquiva o anterior);
 * depois de publicada a versão é imutável, porque o texto que o paciente aceitou precisa continuar recuperável.
 * O texto é guardado e exibido como TEXTO PURO (sem HTML), e cada publicação é auditada.
 */
@Service
public class TermoService {

	static final int MIN = 100;
	static final int MAX = 20_000;

	private final TermoVersaoRepository repo;
	private final TermoConsentimento vigente;
	private final AuditService auditoria;
	private final Clock clock;

	public TermoService(TermoVersaoRepository repo, TermoConsentimento vigente, AuditService auditoria, Clock clock) {
		this.repo = repo;
		this.vigente = vigente;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	public List<TermoVersao> listar() {
		return repo.findAllByOrderByCriadoEmDesc();
	}

	@Transactional
	public TermoVersao criarRascunho(String ator, String texto) {
		String limpo = validar(texto);
		TermoVersao t = repo.save(new TermoVersao(proximaVersao(), limpo, hash(limpo), Status.RASCUNHO, ator, Instant.now(clock)));
		auditoria.registrar(ator, "TERMO_RASCUNHO_CRIADO", "termo/" + t.getVersao());
		return t;
	}

	@Transactional
	public TermoVersao editarRascunho(String ator, UUID id, String texto) {
		TermoVersao t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (t.getStatus() != Status.RASCUNHO) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Uma versão publicada não pode ser alterada: crie uma nova versão.");
		}
		String limpo = validar(texto);
		t.reescrever(limpo, hash(limpo));
		auditoria.registrar(ator, "TERMO_RASCUNHO_EDITADO", "termo/" + t.getVersao());
		return t;
	}

	@Transactional
	public TermoVersao publicar(String ator, UUID id) {
		TermoVersao t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (t.getStatus() != Status.RASCUNHO) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Só um rascunho pode ser publicado.");
		}
		repo.findByStatus(Status.VIGENTE).ifPresent(atual -> {
			atual.arquivar();
			repo.saveAndFlush(atual); // o índice único admite um só vigente: arquiva antes de publicar
		});
		t.publicar(Instant.now(clock));
		repo.saveAndFlush(t);
		vigente.invalidar();
		auditoria.registrar(ator, "TERMO_PUBLICADO", "termo/" + t.getVersao());
		return t;
	}

	@Transactional
	public void excluirRascunho(String ator, UUID id) {
		TermoVersao t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (t.getStatus() != Status.RASCUNHO) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Só um rascunho pode ser excluído.");
		}
		repo.delete(t);
		auditoria.registrar(ator, "TERMO_RASCUNHO_EXCLUIDO", "termo/" + t.getVersao());
	}

	private String proximaVersao() {
		int maior = repo.findAll().stream().map(TermoVersao::getVersao).filter(v -> v.matches("v\\d+"))
				.mapToInt(v -> Integer.parseInt(v.substring(1))).max().orElse(0);
		return "v" + (maior + 1);
	}

	/** Texto puro: normaliza quebras de linha, recusa caracteres de controle (menos \n e \t) e limita o tamanho. */
	static String validar(String texto) {
		String t = texto == null ? "" : texto.replace("\r\n", "\n").replace('\r', '\n').strip();
		if (t.length() < MIN || t.length() > MAX) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "O texto do termo precisa ter de " + MIN + " a " + MAX + " caracteres.");
		}
		if (t.chars().anyMatch(c -> (c < 0x20 && c != '\n' && c != '\t') || c == 0x7f)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "O texto do termo tem caracteres inválidos.");
		}
		return t;
	}

	public static String hash(String texto) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
