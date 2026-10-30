package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import org.hibernate.Length;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "transcricao")
public class Transcricao {

	@Id
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "consulta_id", nullable = false, unique = true)
	private Consulta consulta;

	/** NVARCHAR(MAX): texto longo sem NCLOB, que o SQL Server trataria como tipo legado. */
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(nullable = false, length = Length.LONG32)
	private String texto;

	@Column(nullable = false, length = 10)
	private String idioma;

	@Column(name = "criada_em", nullable = false)
	private Instant criadaEm;

	/** As mesmas falas de {@code texto}, em JSON, com papel e tempo de cada uma. */
	@JdbcTypeCode(SqlTypes.NVARCHAR)
	@Column(nullable = false, length = Length.LONG32)
	private String segmentos = "[]";

	@Column(name = "duracao_ms", nullable = false)
	private long duracaoMs;

	protected Transcricao() {
	}

	public Transcricao(Consulta consulta, String texto, String idioma, Instant criadaEm) {
		this.id = UUID.randomUUID();
		this.consulta = consulta;
		this.texto = texto;
		this.idioma = idioma;
		this.criadaEm = criadaEm.truncatedTo(ChronoUnit.MILLIS);
	}

	public Transcricao(Consulta consulta, String texto, String segmentos, long duracaoMs, String idioma, Instant criadaEm) {
		this(consulta, texto, idioma, criadaEm);
		this.segmentos = segmentos;
		this.duracaoMs = duracaoMs;
	}

	public String getSegmentos() {
		return segmentos;
	}

	public long getDuracaoMs() {
		return duracaoMs;
	}

	public UUID getId() {
		return id;
	}

	public Consulta getConsulta() {
		return consulta;
	}

	public String getTexto() {
		return texto;
	}

	public String getIdioma() {
		return idioma;
	}

	public Instant getCriadaEm() {
		return criadaEm;
	}
}
