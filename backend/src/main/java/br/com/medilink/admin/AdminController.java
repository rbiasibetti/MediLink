package br.com.medilink.admin;

import br.com.medilink.admin.AdminAuthService.Resultado;
import br.com.medilink.domain.ApiKey;
import br.com.medilink.domain.Integrador;
import br.com.medilink.domain.JobStatus;
import br.com.medilink.domain.TermoVersao;
import br.com.medilink.integracao.ApiKeyService;
import br.com.medilink.repository.AdministradorRepository;
import br.com.medilink.repository.AudioTransitorioRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.TranscricaoJobRepository;
import br.com.medilink.service.AuditService;
import br.com.medilink.webhook.WebhookDestino;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * API administrativa (T18). Tudo exige a sessão do administrador (senha + TOTP). Os segredos (API key, segredo do
 * webhook) aparecem UMA vez, na resposta que os cria. Nenhuma resposta traz dado de paciente.
 */
@RestController
@RequestMapping("/api/admin")
class AdminController {

	private static final SecureRandom RANDOM = new SecureRandom();

	record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String senha, @Size(max = 10) String codigo) {
		@Override
		public String toString() {
			return "LoginRequest[]";
		}
	}

	record LoginResposta(String etapa, String token, Instant expiraEm, String segredoTotp, String otpauthUri) {
		@Override
		public String toString() {
			return "LoginResposta[etapa=" + etapa + "]";
		}
	}

	record NovoAdmin(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 128) String senha) {
		@Override
		public String toString() {
			return "NovoAdmin[]";
		}
	}

	record AdminResposta(UUID id, String email, boolean ativo, boolean totpConfirmado, Instant criadoEm) {
	}

	record NovoIntegrador(@NotBlank @Size(max = 200) String nome) {
	}

	record AlterarIntegrador(Boolean ativo) {
	}

	record WebhookConfig(@Size(max = 500) String baseUrl, @NotNull Boolean ativo, Boolean novoSegredo) {
	}

	record IntegradorResposta(UUID id, String nome, boolean ativo, String webhookBaseUrl, boolean webhookAtivo, boolean webhookTemSegredo, Instant criadoEm) {
	}

	record WebhookResposta(IntegradorResposta integrador, String segredo) {
		@Override
		public String toString() {
			return "WebhookResposta[]";
		}
	}

	record NovaKey(@NotBlank @Size(max = 100) String nome, @NotNull Set<String> escopos, Integer diasDeValidade) {
	}

	record KeyCriada(UUID id, String chave) {
		@Override
		public String toString() {
			return "KeyCriada[id=" + id + "]";
		}
	}

	record KeyResposta(UUID id, String nome, String escopos, boolean ativa, Instant criadaEm, Instant expiraEm, Instant ultimoUsoEm, Instant revogadaEm) {
	}

	record TextoTermo(@NotBlank String texto) {
	}

	record TermoResposta(UUID id, String versao, String status, String texto, String hash, String criadoPor, Instant criadoEm, Instant publicadoEm) {
	}

	private final AdminAuthService auth;
	private final AdministradorRepository admins;
	private final PainelService painel;
	private final IntegradorRepository integradores;
	private final ApiKeyService keys;
	private final TermoService termos;
	private final TranscricaoJobRepository jobs;
	private final AudioTransitorioRepository audios;
	private final br.com.medilink.repository.WebhookEntregaRepository entregasRepo;
	private final AuditService auditoria;
	private final Clock clock;
	private final boolean webhookPermiteHttp;

	AdminController(AdminAuthService auth, AdministradorRepository admins, PainelService painel, IntegradorRepository integradores,
			ApiKeyService keys, TermoService termos, TranscricaoJobRepository jobs, AudioTransitorioRepository audios,
			br.com.medilink.repository.WebhookEntregaRepository entregasRepo, AuditService auditoria, Clock clock, @Value("${medilink.webhook.modo-dev:false}") boolean webhookPermiteHttp) {
		this.auth = auth;
		this.admins = admins;
		this.painel = painel;
		this.integradores = integradores;
		this.keys = keys;
		this.termos = termos;
		this.jobs = jobs;
		this.audios = audios;
		this.entregasRepo = entregasRepo;
		this.auditoria = auditoria;
		this.clock = clock;
		this.webhookPermiteHttp = webhookPermiteHttp;
	}

	// ------------------------------------------------------------ login e administradores

	@PostMapping("/login")
	LoginResposta login(@Valid @RequestBody LoginRequest req) {
		Resultado r = auth.entrar(req.email(), req.senha(), req.codigo());
		return new LoginResposta(r.etapa().name(), r.token(), r.expiraEm(), r.segredo(), r.otpauthUri());
	}

	@GetMapping("/administradores")
	List<AdminResposta> administradores(@AuthenticationPrincipal AdminPrincipal eu) {
		return auth.listar().stream().map(a -> new AdminResposta(a.getId(), a.getEmail(), a.isAtivo(), a.isTotpConfirmado(), a.getCriadoEm())).toList();
	}

	@PostMapping("/administradores")
	ResponseEntity<AdminResposta> novoAdmin(@Valid @RequestBody NovoAdmin req, @AuthenticationPrincipal AdminPrincipal eu) {
		var a = auth.criar(eu.email(), req.email(), req.senha());
		return ResponseEntity.status(HttpStatus.CREATED).body(new AdminResposta(a.getId(), a.getEmail(), a.isAtivo(), a.isTotpConfirmado(), a.getCriadoEm()));
	}

	@DeleteMapping("/administradores/{id}")
	ResponseEntity<Void> desativarAdmin(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		auth.desativar(eu.email(), id, eu.id());
		return ResponseEntity.noContent().build();
	}

	// ------------------------------------------------------------ painel

	@GetMapping("/painel")
	Object painel(@RequestParam(defaultValue = "30") int dias, @AuthenticationPrincipal AdminPrincipal eu) {
		auditoria.registrar(eu.email(), "PAINEL_ACESSADO", "painel");
		return painel.resumo(Math.max(1, Math.min(dias, 365)));
	}

	/** Recoloca na fila um job que falhou (o áudio foi mantido). Só se ainda houver áudio a transcrever. */
	@PostMapping("/jobs/{consultaId}/reenviar")
	@Transactional
	ResponseEntity<Void> reenviar(@PathVariable UUID consultaId, @AuthenticationPrincipal AdminPrincipal eu) {
		var job = jobs.findByConsultaId(consultaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (job.getStatus() != JobStatus.FALHA_NO_ENVIO && job.getStatus() != JobStatus.FALHA_NO_WORKER) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Só um job que falhou pode ser reenviado.");
		}
		boolean temAudio = audios.findByConsultaIdOrderByIniciadoEm(consultaId).stream()
				.anyMatch(a -> a.getStatus() != br.com.medilink.domain.AudioStatus.DESCARTADO);
		if (!temAudio) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O áudio desta consulta não existe mais: não há o que reenviar.");
		}
		job.reenfileirar(Instant.now(clock));
		auditoria.registrar(eu.email(), "JOB_REENVIADO", "consulta/" + consultaId);
		return ResponseEntity.noContent().build();
	}

	// ------------------------------------------------------------ webhook (T19)

	record EntregaResposta(UUID id, String evento, String motivo, String status, int tentativas, Integer ultimoStatusHttp, String ultimoErro,
			Instant criadoEm, Instant proximaTentativaEm, Instant entregueEm, UUID consultaId, UUID integradorId) {
	}

	/** Estado das entregas (sem o corpo, que tem o texto da consulta). */
	@GetMapping("/webhooks/entregas")
	List<EntregaResposta> entregas(@RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limite) {
		var pagina = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limite, 200)));
		var lista = status == null || status.isBlank() ? entregasRepo.findAllByOrderByCriadoEmDesc(pagina)
				: entregasRepo.findByStatusOrderByCriadoEmDesc(parseStatus(status), pagina);
		return lista.stream().map(e -> new EntregaResposta(e.getId(), e.getEvento(), e.getMotivo(), e.getStatus().name(), e.getTentativas(),
				e.getUltimoStatusHttp(), e.getUltimoErro(), e.getCriadoEm(), e.getProximaTentativaEm(), e.getEntregueEm(), e.getConsultaId(), e.getIntegradorId())).toList();
	}

	@PostMapping("/webhooks/entregas/{id}/reenviar")
	@Transactional
	ResponseEntity<Void> reenviarEntrega(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		var e = entregasRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (e.getStatus() != br.com.medilink.domain.WebhookEntrega.Status.FALHA) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Só uma entrega que falhou pode ser reenviada.");
		}
		e.reenfileirar(Instant.now(clock));
		auditoria.registrar(eu.email(), "WEBHOOK_REENVIADO", "consulta/" + e.getConsultaId());
		return ResponseEntity.noContent().build();
	}

	private static br.com.medilink.domain.WebhookEntrega.Status parseStatus(String s) {
		try {
			return br.com.medilink.domain.WebhookEntrega.Status.valueOf(s.toUpperCase());
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Status inválido.");
		}
	}

	// ------------------------------------------------------------ sistemas integrados e API keys

	@GetMapping("/integradores")
	List<IntegradorResposta> integradores() {
		return integradores.findAll().stream().map(AdminController::resp).toList();
	}

	@PostMapping("/integradores")
	@Transactional
	ResponseEntity<IntegradorResposta> novoIntegrador(@Valid @RequestBody NovoIntegrador req, @AuthenticationPrincipal AdminPrincipal eu) {
		Integrador i = integradores.save(new Integrador(req.nome().strip(), Instant.now(clock)));
		auditoria.registrar(eu.email(), "INTEGRADOR_CRIADO", "integrador/" + i.getId());
		return ResponseEntity.status(HttpStatus.CREATED).body(resp(i));
	}

	@PatchMapping("/integradores/{id}")
	@Transactional
	IntegradorResposta alterarIntegrador(@PathVariable UUID id, @RequestBody AlterarIntegrador req, @AuthenticationPrincipal AdminPrincipal eu) {
		Integrador i = integradores.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		if (req.ativo() != null) {
			if (req.ativo()) {
				i.ativar();
			}
			else {
				i.desativar();
			}
			auditoria.registrar(eu.email(), req.ativo() ? "INTEGRADOR_ATIVADO" : "INTEGRADOR_DESATIVADO", "integrador/" + id);
		}
		return resp(i);
	}

	/** Cadastra a URL base e liga/desliga o webhook. O segredo da assinatura é gerado aqui e mostrado só uma vez. */
	@PutMapping("/integradores/{id}/webhook")
	@Transactional
	WebhookResposta webhook(@PathVariable UUID id, @Valid @RequestBody WebhookConfig req, @AuthenticationPrincipal AdminPrincipal eu) {
		Integrador i = integradores.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		String base = req.baseUrl() == null || req.baseUrl().isBlank() ? null : WebhookDestino.validarBase(req.baseUrl(), webhookPermiteHttp).toString();
		if (req.ativo() && base == null) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe a URL base para ativar o webhook.");
		}
		String segredo = null;
		if (Boolean.TRUE.equals(req.novoSegredo()) || (req.ativo() && i.getWebhookSegredo() == null)) {
			byte[] b = new byte[32];
			RANDOM.nextBytes(b);
			segredo = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
		}
		i.configurarWebhook(base, segredo, req.ativo());
		auditoria.registrar(eu.email(), "WEBHOOK_CONFIGURADO", "integrador/" + id);
		return new WebhookResposta(resp(i), segredo);
	}

	@GetMapping("/integradores/{id}/chaves")
	List<KeyResposta> chaves(@PathVariable UUID id) {
		return keys.listar(id).stream().map(AdminController::resp).toList();
	}

	@PostMapping("/integradores/{id}/chaves")
	ResponseEntity<KeyCriada> novaKey(@PathVariable UUID id, @Valid @RequestBody NovaKey req, @AuthenticationPrincipal AdminPrincipal eu) {
		Instant expira = req.diasDeValidade() == null ? null : Instant.now(clock).plus(Duration.ofDays(Math.max(1, Math.min(req.diasDeValidade(), 3650))));
		var k = keys.criar(eu.email(), id, req.nome().strip(), req.escopos(), expira);
		return ResponseEntity.status(HttpStatus.CREATED).body(new KeyCriada(k.keyId(), k.chave()));
	}

	@PostMapping("/chaves/{keyId}/revogar")
	ResponseEntity<Void> revogar(@PathVariable UUID keyId, @AuthenticationPrincipal AdminPrincipal eu) {
		keys.revogar(eu.email(), keyId);
		return ResponseEntity.noContent().build();
	}

	// ------------------------------------------------------------ termo de consentimento

	@GetMapping("/termos")
	List<TermoResposta> termos() {
		return termos.listar().stream().map(AdminController::resp).toList();
	}

	@PostMapping("/termos")
	ResponseEntity<TermoResposta> novoTermo(@Valid @RequestBody TextoTermo req, @AuthenticationPrincipal AdminPrincipal eu) {
		return ResponseEntity.status(HttpStatus.CREATED).body(resp(termos.criarRascunho(eu.email(), req.texto())));
	}

	@PutMapping("/termos/{id}")
	TermoResposta editarTermo(@PathVariable UUID id, @Valid @RequestBody TextoTermo req, @AuthenticationPrincipal AdminPrincipal eu) {
		return resp(termos.editarRascunho(eu.email(), id, req.texto()));
	}

	@PostMapping("/termos/{id}/publicar")
	TermoResposta publicarTermo(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		return resp(termos.publicar(eu.email(), id));
	}

	@DeleteMapping("/termos/{id}")
	ResponseEntity<Void> excluirTermo(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal eu) {
		termos.excluirRascunho(eu.email(), id);
		return ResponseEntity.noContent().build();
	}

	// ------------------------------------------------------------ conversões

	private static IntegradorResposta resp(Integrador i) {
		return new IntegradorResposta(i.getId(), i.getNome(), i.isAtivo(), i.getWebhookBaseUrl(), i.isWebhookAtivo(), i.getWebhookSegredo() != null, i.getCriadoEm());
	}

	private static KeyResposta resp(ApiKey k) {
		return new KeyResposta(k.getId(), k.getNome(), k.getEscopos(), k.isAtiva(), k.getCriadaEm(), k.getExpiraEm(), k.getUltimoUsoEm(), k.getRevogadaEm());
	}

	private static TermoResposta resp(TermoVersao t) {
		return new TermoResposta(t.getId(), t.getVersao(), t.getStatus().name(), t.getTexto(), t.getHashTexto(), t.getCriadoPor(), t.getCriadoEm(), t.getPublicadoEm());
	}
}
