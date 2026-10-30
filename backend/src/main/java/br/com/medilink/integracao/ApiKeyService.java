package br.com.medilink.integracao;

import br.com.medilink.domain.ApiKey;
import br.com.medilink.domain.Integrador;
import br.com.medilink.repository.ApiKeyRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.service.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * API keys dos sistemas integrados (T16). Formato {@code mlk_<idDaKey>_<segredo>}: o id localiza a linha e o segredo
 * (256 bits) nunca é guardado, só o seu SHA-256 (suficiente por ser aleatório e longo; não há o que "adivinhar").
 * A comparação é em tempo constante. A key completa só existe na resposta da criação.
 */
@Service
public class ApiKeyService {

	public static final Set<String> ESCOPOS_VALIDOS = Set.of("agendamento:escrever", "agendamento:ler", "sala:iniciar");
	private static final String PREFIXO = "mlk_";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** Quem a chamada autenticada representa; o {@code keyId} aparece na auditoria, nunca o segredo. */
	public record Autenticada(UUID keyId, UUID integradorId, Set<String> escopos) {
	}

	public record Criada(UUID keyId, String chave) {
		@Override
		public String toString() {
			return "Criada[keyId=" + keyId + "]";
		}
	}

	private final ApiKeyRepository keys;
	private final IntegradorRepository integradores;
	private final AuditService auditoria;
	private final Clock clock;

	public ApiKeyService(ApiKeyRepository keys, IntegradorRepository integradores, AuditService auditoria, Clock clock) {
		this.keys = keys;
		this.integradores = integradores;
		this.auditoria = auditoria;
		this.clock = clock;
	}

	@Transactional
	public Criada criar(String ator, UUID integradorId, String nome, Set<String> escopos, Instant expiraEm) {
		integradores.findById(integradorId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (escopos.isEmpty() || !ESCOPOS_VALIDOS.containsAll(escopos)) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Escopo inválido. Válidos: " + ESCOPOS_VALIDOS);
		}
		byte[] bruto = new byte[32];
		RANDOM.nextBytes(bruto);
		String segredo = Base64.getUrlEncoder().withoutPadding().encodeToString(bruto);
		UUID id = UUID.randomUUID();
		keys.save(new ApiKey(id, integradorId, nome, hash(segredo), String.join(",", new java.util.TreeSet<>(escopos)),
				Instant.now(clock), expiraEm));
		auditoria.registrar(ator, "API_KEY_CRIADA", "api-key/" + id);
		return new Criada(id, PREFIXO + id.toString().replace("-", "") + "_" + segredo);
	}

	@Transactional
	public void revogar(String ator, UUID keyId) {
		ApiKey k = keys.findById(keyId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (k.isAtiva()) {
			k.revogar(Instant.now(clock));
			auditoria.registrar(ator, "API_KEY_REVOGADA", "api-key/" + keyId);
		}
	}

	public List<ApiKey> listar(UUID integradorId) {
		return keys.findByIntegradorIdOrderByCriadaEm(integradorId);
	}

	/**
	 * Valida o valor do cabeçalho. Qualquer problema (formato, key inexistente, revogada, vencida, segredo errado,
	 * sistema desativado) resulta em vazio: o chamador responde sempre o mesmo 401, sem dizer qual foi.
	 */
	@Transactional
	public Optional<Autenticada> autenticar(String cabecalho) {
		if (cabecalho == null || !cabecalho.startsWith(PREFIXO) || cabecalho.length() > 200) {
			return Optional.empty();
		}
		String[] partes = cabecalho.substring(PREFIXO.length()).split("_", 2);
		if (partes.length != 2 || partes[0].length() != 32 || partes[1].isEmpty()) {
			return Optional.empty();
		}
		UUID id;
		try {
			id = UUID.fromString(partes[0].replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5"));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		Instant agora = Instant.now(clock);
		Optional<ApiKey> achada = keys.findById(id);
		// Mesmo custo com ou sem key: compara contra um hash fixo quando não existe.
		String esperado = achada.map(ApiKey::getHashSegredo).orElse("0".repeat(64));
		boolean confere = MessageDigest.isEqual(esperado.getBytes(StandardCharsets.US_ASCII), hash(partes[1]).getBytes(StandardCharsets.US_ASCII));
		if (achada.isEmpty() || !confere || !achada.get().utilizavel(agora)) {
			return Optional.empty();
		}
		ApiKey k = achada.get();
		Integrador integrador = integradores.findById(k.getIntegradorId()).orElse(null);
		if (integrador == null || !integrador.isAtivo()) {
			return Optional.empty();
		}
		// Um registro de uso por minuto no máximo: não vira escrita a cada requisição.
		if (k.getUltimoUsoEm() == null || k.getUltimoUsoEm().isBefore(agora.minusSeconds(60))) {
			k.registrarUso(agora);
		}
		return Optional.of(new Autenticada(k.getId(), k.getIntegradorId(), Set.copyOf(Arrays.asList(k.getEscopos().split(",")))));
	}

	static String hash(String segredo) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(segredo.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
