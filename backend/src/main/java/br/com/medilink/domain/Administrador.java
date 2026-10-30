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

/** Administrador da plataforma (T18): senha (BCrypt) + TOTP. O segredo TOTP fica cifrado em repouso. */
@Entity
@Table(name = "administrador")
public class Administrador {

	@Id
	private UUID id;

	@Nationalized
	@Column(nullable = false, unique = true, length = 254)
	private String email;

	@Column(name = "senha_hash", nullable = false, length = 100)
	private String senhaHash;

	@Convert(converter = CifraTextoConverter.class)
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(name = "totp_segredo")
	private String totpSegredo;

	@Column(name = "totp_confirmado", nullable = false)
	private boolean totpConfirmado;

	/** Último passo de 30 s aceito: o mesmo código não vale duas vezes (replay). */
	@Column(name = "totp_ultimo_passo")
	private Long totpUltimoPasso;

	@Column(nullable = false)
	private boolean ativo;

	@Column(name = "falhas_seguidas", nullable = false)
	private int falhasSeguidas;

	@Column(name = "bloqueado_ate")
	private Instant bloqueadoAte;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	protected Administrador() {
	}

	public Administrador(String email, String senhaHash, Instant criadoEm) {
		this.id = UUID.randomUUID();
		this.email = email;
		this.senhaHash = senhaHash;
		this.ativo = true;
		this.criadoEm = criadoEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public boolean bloqueado(Instant agora) {
		return bloqueadoAte != null && bloqueadoAte.isAfter(agora);
	}

	public void registrarFalha(Instant agora, int maximo, java.time.Duration bloqueio) {
		falhasSeguidas++;
		if (falhasSeguidas >= maximo) {
			bloqueadoAte = agora.plus(bloqueio).truncatedTo(ChronoUnit.MILLIS);
			falhasSeguidas = 0;
		}
	}

	public void registrarSucesso() {
		falhasSeguidas = 0;
		bloqueadoAte = null;
	}

	public void definirSegredoTotp(String segredo) {
		this.totpSegredo = segredo;
		this.totpConfirmado = false;
		this.totpUltimoPasso = null;
	}

	public void confirmarTotp(long passo) {
		this.totpConfirmado = true;
		this.totpUltimoPasso = passo;
	}

	public void usarPasso(long passo) {
		this.totpUltimoPasso = passo;
	}

	public void desativar() {
		this.ativo = false;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getSenhaHash() {
		return senhaHash;
	}

	public String getTotpSegredo() {
		return totpSegredo;
	}

	public boolean isTotpConfirmado() {
		return totpConfirmado;
	}

	public Long getTotpUltimoPasso() {
		return totpUltimoPasso;
	}

	public boolean isAtivo() {
		return ativo;
	}

	public Instant getBloqueadoAte() {
		return bloqueadoAte;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}
}
