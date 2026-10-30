package br.com.medilink.service;

import br.com.medilink.config.ConsultaProperties;
import br.com.medilink.domain.Agendamento;
import br.com.medilink.domain.Consulta;
import br.com.medilink.domain.ConsultaStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * O link do paciente vale até o horário agendado mais a tolerância (T17), mas só enquanto a consulta não começou:
 * quem está numa consulta em andamento ou encerrada por queda não perde a entrada por causa do relógio.
 */
@Component
public class ValidadeDoLink {

	private final ConsultaProperties props;
	private final Clock clock;

	public ValidadeDoLink(ConsultaProperties props, Clock clock) {
		this.props = props;
		this.clock = clock;
	}

	public boolean expirado(Agendamento a, Consulta c) {
		boolean naoComecou = c.getStatus() == ConsultaStatus.CRIADA || c.getStatus() == ConsultaStatus.AGUARDANDO_PACIENTE;
		return naoComecou && Instant.now(clock).isAfter(a.getDataHora().plus(props.validadeDoLinkDoPaciente()));
	}
}
