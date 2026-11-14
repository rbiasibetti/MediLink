package br.com.medilink.email;

import br.com.medilink.domain.ConfiguracaoSmtp;
import br.com.medilink.repository.ConfiguracaoSmtpRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Fornece o {@link JavaMailSender} e o remetente usados no envio do link. Se a administração configurou o SMTP
 * (tabela configuracao_smtp), usa esses parâmetros; caso contrário, cai nos valores das variáveis de ambiente
 * (o relay de desenvolvimento, por exemplo). Recarrega quando a administração salva uma configuração nova.
 */
@Component
public class ProvedorDeEmail {

	private final JavaMailSender padraoDoAmbiente;
	private final String remetentePadrao;
	private final ConfiguracaoSmtpRepository repo;

	private volatile JavaMailSender sender;
	private volatile String remetente;

	public ProvedorDeEmail(JavaMailSender padraoDoAmbiente, ConfiguracaoSmtpRepository repo,
			@Value("${medilink.mail.from}") String remetentePadrao) {
		this.padraoDoAmbiente = padraoDoAmbiente;
		this.repo = repo;
		this.remetentePadrao = remetentePadrao;
		recarregar();
	}

	public JavaMailSender sender() {
		return sender;
	}

	public String remetente() {
		return remetente;
	}

	/** Relê a configuração do banco (chamado na subida e sempre que a administração salva). */
	public final synchronized void recarregar() {
		ConfiguracaoSmtp cfg = repo.atual().filter(c -> c.getHost() != null && !c.getHost().isBlank()).orElse(null);
		if (cfg == null) {
			this.sender = padraoDoAmbiente;
			this.remetente = remetentePadrao;
			return;
		}
		this.sender = construir(cfg);
		this.remetente = cfg.getRemetente() != null && !cfg.getRemetente().isBlank() ? cfg.getRemetente() : remetentePadrao;
	}

	/** Constrói um sender a partir de parâmetros avulsos (usado também no teste de conexão, sem persistir). */
	public JavaMailSender construir(ConfiguracaoSmtp cfg) {
		JavaMailSenderImpl s = new JavaMailSenderImpl();
		s.setHost(cfg.getHost());
		s.setPort(cfg.getPorta());
		s.setDefaultEncoding("UTF-8");
		if (cfg.getUsuario() != null && !cfg.getUsuario().isBlank()) {
			s.setUsername(cfg.getUsuario());
		}
		if (cfg.getSenha() != null) {
			s.setPassword(cfg.getSenha());
		}
		Properties p = s.getJavaMailProperties();
		p.put("mail.smtp.auth", Boolean.toString(cfg.isAutenticar()));
		p.put("mail.smtp.starttls.enable", Boolean.toString(cfg.isStarttls()));
		p.put("mail.smtp.starttls.required", Boolean.toString(cfg.isStarttls()));
		p.put("mail.smtp.connectiontimeout", "10000");
		p.put("mail.smtp.timeout", "15000");
		p.put("mail.smtp.writetimeout", "15000");
		return s;
	}
}
