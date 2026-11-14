package br.com.medilink.domain;

import br.com.medilink.crypto.CifraTextoConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Nationalized;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Parâmetros do servidor SMTP de envio do link ao paciente, configuráveis pela administração (antes só por variáveis
 * de ambiente). Uma única linha no banco (id fixo). A senha fica cifrada em repouso.
 */
@Entity
@Table(name = "configuracao_smtp")
public class ConfiguracaoSmtp {

	/** Id fixo: a tabela tem sempre no máximo uma linha (garantido também por CHECK na migração). */
	public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Id
	private UUID id = ID;

	@Nationalized
	@Column(nullable = false, length = 255)
	private String host;

	@Column(nullable = false)
	private int porta;

	@Nationalized
	@Column(length = 255)
	private String usuario;

	@Convert(converter = CifraTextoConverter.class)
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(name = "senha_cifrada")
	private String senha;

	@Column(nullable = false)
	private boolean autenticar;

	@Column(nullable = false)
	private boolean starttls;

	@Nationalized
	@Column(nullable = false, length = 255)
	private String remetente;

	@Nationalized
	@Column(name = "atualizado_por", nullable = false, length = 254)
	private String atualizadoPor;

	@Column(name = "atualizado_em", nullable = false)
	private Instant atualizadoEm;

	protected ConfiguracaoSmtp() {
	}

	public ConfiguracaoSmtp(String host, int porta, String usuario, String senha, boolean autenticar, boolean starttls,
			String remetente, String atualizadoPor, Instant agora) {
		this.id = ID;
		aplicar(host, porta, usuario, senha, autenticar, starttls, remetente, atualizadoPor, agora);
	}

	public void aplicar(String host, int porta, String usuario, String senha, boolean autenticar, boolean starttls,
			String remetente, String atualizadoPor, Instant agora) {
		this.host = host;
		this.porta = porta;
		this.usuario = usuario;
		if (senha != null) { // senha nula = manter a atual (o formulário não reenvia a senha salva)
			this.senha = senha.isBlank() ? null : senha;
		}
		this.autenticar = autenticar;
		this.starttls = starttls;
		this.remetente = remetente;
		this.atualizadoPor = atualizadoPor;
		this.atualizadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	public UUID getId() {
		return id;
	}

	public String getHost() {
		return host;
	}

	public int getPorta() {
		return porta;
	}

	public String getUsuario() {
		return usuario;
	}

	public String getSenha() {
		return senha;
	}

	public boolean temSenha() {
		return senha != null && !senha.isBlank();
	}

	public boolean isAutenticar() {
		return autenticar;
	}

	public boolean isStarttls() {
		return starttls;
	}

	public String getRemetente() {
		return remetente;
	}

	public String getAtualizadoPor() {
		return atualizadoPor;
	}

	public Instant getAtualizadoEm() {
		return atualizadoEm;
	}
}
