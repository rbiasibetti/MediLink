package br.com.medilink.service;

import br.com.medilink.service.AgendamentoService.ParaEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Envia o link da consulta ao paciente (RF-03). O resultado, de sucesso ou falha, vai para a auditoria;
 * o endereço do paciente e o CPF nunca entram em log nem na auditoria.
 */
@Service
public class EmailService {

	private static final DateTimeFormatter QUANDO = DateTimeFormatter
			.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR"))
			.withZone(ZoneId.of("America/Sao_Paulo"));

	private final JavaMailSender mail;
	private final AgendamentoService agendamentos;
	private final AuditService auditoria;
	private final Clock clock;
	private final String remetente;

	public EmailService(JavaMailSender mail, AgendamentoService agendamentos, AuditService auditoria, Clock clock,
			@Value("${medilink.mail.from}") String remetente) {
		this.mail = mail;
		this.agendamentos = agendamentos;
		this.auditoria = auditoria;
		this.clock = clock;
		this.remetente = remetente;
	}

	/** Sem @Transactional de propósito: a falha de envio precisa ser auditada mesmo lançando exceção. */
	public Instant enviarLink(String medicoEmail, UUID agendamentoId) {
		ParaEmail dados = agendamentos.paraEnvioDeEmail(medicoEmail, agendamentoId);
		if (dados.pacienteEmail() == null || dados.pacienteEmail().isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "O agendamento não tem e-mail do paciente.");
		}
		String recurso = "agendamento/" + agendamentoId;
		try {
			mail.send(montar(dados));
		}
		catch (MailException | MessagingException e) {
			auditoria.registrar(medicoEmail, "EMAIL_LINK_FALHA", recurso);
			// Sem a causa na resposta: mensagens de SMTP podem citar o destinatário.
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível enviar o e-mail.");
		}
		auditoria.registrar(medicoEmail, "EMAIL_LINK_ENVIADO", recurso);
		return Instant.now(clock);
	}

	private MimeMessage montar(ParaEmail d) throws MessagingException {
		MimeMessage mensagem = mail.createMimeMessage();
		MimeMessageHelper h = new MimeMessageHelper(mensagem, false, "UTF-8");
		h.setFrom(remetente);
		h.setTo(d.pacienteEmail());
		h.setSubject("Sua teleconsulta com " + d.medicoNome() + " em " + QUANDO.format(d.dataHora()));
		h.setText(corpo(d), false);
		return mensagem;
	}

	static String corpo(ParaEmail d) {
		StringBuilder t = new StringBuilder();
		t.append("Olá, ").append(d.pacienteNome()).append(".\n\n");
		t.append(d.medicoNome()).append(" agendou uma teleconsulta com você para ")
				.append(QUANDO.format(d.dataHora())).append(".\n\n");
		t.append("No horário marcado, acesse o link abaixo:\n").append(d.link()).append("\n\n");
		if (d.cpfValidationEnabled()) {
			t.append("Tenha seu CPF em mãos: ele será pedido para confirmar a sua identidade.\n");
		}
		t.append("Ao entrar, você aguardará na sala de espera até o médico liberar a sua entrada. ")
				.append("Antes de começar, será pedido o seu consentimento para a captura do áudio e a transcrição da consulta.\n\n");
		t.append("Se você não esperava este e-mail, ignore-o.\n");
		return t.toString();
	}
}
