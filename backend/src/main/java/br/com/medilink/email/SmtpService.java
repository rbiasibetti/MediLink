package br.com.medilink.email;

import br.com.medilink.domain.ConfiguracaoSmtp;
import br.com.medilink.repository.ConfiguracaoSmtpRepository;
import br.com.medilink.service.AuditService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;

/** Configuração do servidor SMTP pela administração: ler, salvar e testar o envio. A senha nunca sai nas respostas. */
@Service
public class SmtpService {

	/** Visão da configuração para a tela; {@code temSenha} em vez da senha; {@code configurado} distingue do padrão do ambiente. */
	public record Visao(String host, int porta, String usuario, boolean temSenha, boolean autenticar, boolean starttls,
			String remetente, boolean configurado, String atualizadoPor, Instant atualizadoEm) {
	}

	public record Dados(String host, int porta, String usuario, String senha, boolean autenticar, boolean starttls, String remetente) {
	}

	private final ConfiguracaoSmtpRepository repo;
	private final ProvedorDeEmail provedor;
	private final AuditService auditoria;
	private final Clock clock;
	private final String hostPadrao;
	private final int portaPadrao;
	private final String remetentePadrao;

	public SmtpService(ConfiguracaoSmtpRepository repo, ProvedorDeEmail provedor, AuditService auditoria, Clock clock,
			@Value("${spring.mail.host:localhost}") String hostPadrao, @Value("${spring.mail.port:1025}") int portaPadrao,
			@Value("${medilink.mail.from}") String remetentePadrao) {
		this.repo = repo;
		this.provedor = provedor;
		this.auditoria = auditoria;
		this.clock = clock;
		this.hostPadrao = hostPadrao;
		this.portaPadrao = portaPadrao;
		this.remetentePadrao = remetentePadrao;
	}

	@Transactional(readOnly = true)
	public Visao atual() {
		return repo.atual()
				.map(c -> new Visao(c.getHost(), c.getPorta(), c.getUsuario(), c.temSenha(), c.isAutenticar(), c.isStarttls(),
						c.getRemetente(), true, c.getAtualizadoPor(), c.getAtualizadoEm()))
				.orElseGet(() -> new Visao(hostPadrao, portaPadrao, "", false, false, false, remetentePadrao, false, null, null));
	}

	@Transactional
	public Visao salvar(String ator, Dados d) {
		if (d.host() == null || d.host().isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe o servidor (host) do SMTP.");
		}
		if (d.porta() < 1 || d.porta() > 65535) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Porta inválida.");
		}
		Instant agora = Instant.now(clock);
		ConfiguracaoSmtp cfg = repo.atual().orElse(null);
		if (cfg == null) {
			cfg = new ConfiguracaoSmtp(d.host().strip(), d.porta(), vazioParaNulo(d.usuario()), d.senha(),
					d.autenticar(), d.starttls(), d.remetente().strip(), ator, agora);
		}
		else {
			cfg.aplicar(d.host().strip(), d.porta(), vazioParaNulo(d.usuario()), d.senha(), d.autenticar(), d.starttls(),
					d.remetente().strip(), ator, agora);
		}
		repo.save(cfg);
		provedor.recarregar();
		auditoria.registrar(ator, "SMTP_CONFIGURADO", "smtp");
		return atual();
	}

	/** Envia um e-mail de teste com a configuração em vigor. Falha de envio é auditada e vira 502, sem a causa (pode citar o destino). */
	public void testar(String ator, String para) {
		try {
			MimeMessage m = provedor.sender().createMimeMessage();
			MimeMessageHelper h = new MimeMessageHelper(m, false, "UTF-8");
			h.setFrom(provedor.remetente());
			h.setTo(para);
			h.setSubject("Teste de configuração do MediLink");
			h.setText("Este é um e-mail de teste do MediLink. Se você o recebeu, o servidor SMTP está configurado corretamente.", false);
			provedor.sender().send(m);
		}
		catch (MailException | MessagingException e) {
			auditoria.registrar(ator, "SMTP_TESTE_FALHA", "smtp");
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível enviar o e-mail de teste.");
		}
		auditoria.registrar(ator, "SMTP_TESTADO", "smtp");
	}

	private static String vazioParaNulo(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}
}
