package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Nationalized;

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
