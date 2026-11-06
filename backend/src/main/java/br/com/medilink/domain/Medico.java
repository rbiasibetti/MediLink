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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "medico")
public class Medico {

	@Id
	private UUID id;

	@Nationalized
	@Column(nullable = false, length = 200)
	private String nome;

	@Nationalized
	@Column(name = "identificador_profissional", nullable = false, length = 50)
	private String identificadorProfissional;

	@Nationalized
	@Column(nullable = false, unique = true, length = 254)
	private String email;

	/** Preenchidos só para médicos criados por um sistema integrado (T16); o login local continua pelo e-mail. */
	@Column(name = "integrador_id")
	private UUID integradorId;

	@Nationalized
	@Column(name = "chave_externa", length = 50)
	private String chaveExterna;

	/** Login por senha (T20). Sem senha, o médico só entra pelo link de uso único do sistema integrado. */
	@Column(name = "senha_hash", length = 100)
	private String senhaHash;

	@Column(nullable = false)
	private boolean ativo = true;

	/** Decisão do administrador: com TOTP habilitado, o login exige o código do aplicativo autenticador. */
	@Column(name = "totp_habilitado", nullable = false)
	private boolean totpHabilitado;

	@Convert(converter = CifraTextoConverter.class)
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(name = "totp_segredo")
	private String totpSegredo;

	@Column(name = "totp_confirmado", nullable = false)
	private boolean totpConfirmado;

	/** Último passo de 30 s aceito: o mesmo código não vale duas vezes (replay). */
	@Column(name = "totp_ultimo_passo")
	private Long totpUltimoPasso;

	@Column(name = "falhas_seguidas", nullable = false)
	private int falhasSeguidas;

	@Column(name = "bloqueado_ate")
	private Instant bloqueadoAte;

	protected Medico() {
	}

	/** Médico de um sistema integrado: o e-mail é sintético (não entregável) e só existe para identificar o login. */
	public static Medico externo(String nome, String chave, String emailSintetico, UUID integradorId) {
		Medico m = new Medico(nome, chave, emailSintetico);
		m.integradorId = integradorId;
		m.chaveExterna = chave;
		return m;
	}

	public void renomear(String novoNome) {
		this.nome = novoNome;
	}

	public void atualizar(String novoNome, String novoIdentificador) {
		this.nome = novoNome;
		this.identificadorProfissional = novoIdentificador;
	}

	public void definirSenha(String hash) {
		this.senhaHash = hash;
		this.falhasSeguidas = 0;
		this.bloqueadoAte = null;
	}

	public void ativar() {
		this.ativo = true;
	}

	public void desativar() {
		this.ativo = false;
	}

	/** O administrador liga o TOTP: o segredo é gerado no próximo login e só vale depois de confirmado com um código. */
	public void habilitarTotp() {
		this.totpHabilitado = true;
		this.totpSegredo = null;
		this.totpConfirmado = false;
		this.totpUltimoPasso = null;
	}

	public void desabilitarTotp() {
		this.totpHabilitado = false;
		this.totpSegredo = null;
		this.totpConfirmado = false;
		this.totpUltimoPasso = null;
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

	public boolean bloqueado(Instant agora) {
		return bloqueadoAte != null && bloqueadoAte.isAfter(agora);
	}

	public void registrarFalha(Instant agora, int maximo, Duration bloqueio) {
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

	public String getSenhaHash() {
		return senhaHash;
	}

	public boolean isAtivo() {
		return ativo;
	}

	public boolean isTotpHabilitado() {
		return totpHabilitado;
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

	public Instant getBloqueadoAte() {
		return bloqueadoAte;
	}

	public UUID getIntegradorId() {
		return integradorId;
	}

	public String getChaveExterna() {
		return chaveExterna;
	}

	public Medico(String nome, String identificadorProfissional, String email) {
		this.id = UUID.randomUUID();
		this.nome = nome;
		this.identificadorProfissional = identificadorProfissional;
		this.email = email;
		this.ativo = true;
	}

	public UUID getId() {
		return id;
	}

	public String getNome() {
		return nome;
	}

	public String getIdentificadorProfissional() {
		return identificadorProfissional;
	}

	public String getEmail() {
		return email;
	}
}
