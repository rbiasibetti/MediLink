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

	protected Medico() {
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
