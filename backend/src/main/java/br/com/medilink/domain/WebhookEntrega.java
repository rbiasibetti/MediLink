package br.com.medilink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Uma entrega de webhook (tabela de saída, T19). Guarda só a referência: o corpo é montado na hora do envio. */
@Entity
@Table(name = "webhook_entrega")
public class WebhookEntrega {

	public enum Status { PENDENTE, ENTREGUE, FALHA }

	@Id
	private UUID id;

	/** Identificador do evento, o mesmo em todas as tentativas: o receptor deduplica por ele. */
	@Column(name = "evento_id", nullable = false)
	private UUID eventoId;

	@Column(name = "agendamento_id", nullable = false)
	private UUID agendamentoId;

	@Column(name = "consulta_id", nullable = false)
	private UUID consultaId;

	@Column(name = "integrador_id", nullable = false)
	private UUID integradorId;

	@Column(nullable = false, length = 40)
	private String evento;

	@Column(length = 40)
	private String motivo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private Status status;

	@Column(nullable = false)
	private int tentativas;

	@Column(name = "proxima_tentativa_em")
	private Instant proximaTentativaEm;

	@Column(name = "ultimo_status_http")
	private Integer ultimoStatusHttp;

	/** Só o tipo do erro, nunca a mensagem (poderia citar o destino ou conteúdo). */
	@Column(name = "ultimo_erro", length = 200)
	private String ultimoErro;

	@Column(name = "criado_em", nullable = false)
	private Instant criadoEm;

	@Column(name = "entregue_em")
	private Instant entregueEm;

	protected WebhookEntrega() {
	}

	public WebhookEntrega(UUID agendamentoId, UUID consultaId, UUID integradorId, String evento, String motivo, Instant agora) {
		this.id = UUID.randomUUID();
		this.eventoId = UUID.randomUUID();
		this.agendamentoId = agendamentoId;
		this.consultaId = consultaId;
		this.integradorId = integradorId;
		this.evento = evento;
		this.motivo = motivo;
		this.status = Status.PENDENTE;
		this.criadoEm = agora.truncatedTo(ChronoUnit.MILLIS);
		this.proximaTentativaEm = this.criadoEm;
	}

	/** "Reserva" a entrega por alguns minutos enquanto uma instância a envia (outra não repete ao mesmo tempo). */
	public void reservar(Instant ate) {
		this.proximaTentativaEm = ate.truncatedTo(ChronoUnit.MILLIS);
	}

	public void entregue(int http, Instant agora) {
		this.tentativas++;
		this.status = Status.ENTREGUE;
		this.ultimoStatusHttp = http;
		this.ultimoErro = null;
		this.entregueEm = agora.truncatedTo(ChronoUnit.MILLIS);
		this.proximaTentativaEm = null;
	}

	public void repetir(Integer http, String tipoDoErro, Instant proxima) {
		this.tentativas++;
		this.ultimoStatusHttp = http;
		this.ultimoErro = limitar(tipoDoErro);
		this.proximaTentativaEm = proxima.truncatedTo(ChronoUnit.MILLIS);
	}

	public void falhar(Integer http, String tipoDoErro) {
		this.tentativas++;
		this.status = Status.FALHA;
		this.ultimoStatusHttp = http;
		this.ultimoErro = limitar(tipoDoErro);
		this.proximaTentativaEm = null;
	}

	/** O administrador manda tentar de novo uma entrega que falhou de vez. */
	public void reenfileirar(Instant agora) {
		this.status = Status.PENDENTE;
		this.tentativas = 0;
		this.ultimoErro = null;
		this.ultimoStatusHttp = null;
		this.proximaTentativaEm = agora.truncatedTo(ChronoUnit.MILLIS);
	}

	private static String limitar(String s) {
		return s == null ? null : (s.length() > 200 ? s.substring(0, 200) : s);
	}

	public UUID getId() {
		return id;
	}

	public UUID getEventoId() {
		return eventoId;
	}

	public UUID getAgendamentoId() {
		return agendamentoId;
	}

	public UUID getConsultaId() {
		return consultaId;
	}

	public UUID getIntegradorId() {
		return integradorId;
	}

	public String getEvento() {
		return evento;
	}

	public String getMotivo() {
		return motivo;
	}

	public Status getStatus() {
		return status;
	}

	public int getTentativas() {
		return tentativas;
	}

	public Instant getProximaTentativaEm() {
		return proximaTentativaEm;
	}

	public Integer getUltimoStatusHttp() {
		return ultimoStatusHttp;
	}

	public String getUltimoErro() {
		return ultimoErro;
	}

	public Instant getCriadoEm() {
		return criadoEm;
	}

	public Instant getEntregueEm() {
		return entregueEm;
	}
}
