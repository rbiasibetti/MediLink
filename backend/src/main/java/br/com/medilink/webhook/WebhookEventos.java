package br.com.medilink.webhook;

import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.WebhookEntrega;
import br.com.medilink.repository.AgendamentoRepository;
import br.com.medilink.repository.ConsultaRepository;
import br.com.medilink.repository.IntegradorRepository;
import br.com.medilink.repository.WebhookEntregaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Produz os eventos do webhook (T19), na mesma transação da mudança que os causa. Só gera quando o agendamento veio de
 * um sistema integrado com webhook ativo E informou um destino: sem isso o fluxo é o de sempre. Um evento por
 * (consulta, tipo): repetir a chamada não duplica a entrega.
 */
@Component
public class WebhookEventos {

	public static final String CONCLUIDA = "transcricao.concluida";
	public static final String INDISPONIVEL = "transcricao.indisponivel";

	private final ConsultaRepository consultas;
	private final AgendamentoRepository agendamentos;
	private final IntegradorRepository integradores;
	private final WebhookEntregaRepository entregas;
	private final Clock clock;

	public WebhookEventos(ConsultaRepository consultas, AgendamentoRepository agendamentos, IntegradorRepository integradores,
			WebhookEntregaRepository entregas, Clock clock) {
		this.consultas = consultas;
		this.agendamentos = agendamentos;
		this.integradores = integradores;
		this.entregas = entregas;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public void registrar(UUID consultaId, String evento, String motivo) {
		var consulta = consultas.findById(consultaId).orElse(null);
		if (consulta == null) {
			return;
		}
		Agendamento ag = consulta.getAgendamento();
		if (ag.getIntegradorId() == null || ag.getWebhookDestino() == null) {
			return;
		}
		var integrador = integradores.findById(ag.getIntegradorId()).orElse(null);
		if (integrador == null || !integrador.isWebhookAtivo() || integrador.getWebhookBaseUrl() == null) {
			return;
		}
		if (entregas.findByConsultaIdAndEvento(consultaId, evento).isEmpty()) {
			entregas.save(new WebhookEntrega(ag.getId(), consultaId, integrador.getId(), evento, motivo, Instant.now(clock)));
		}
	}
}
