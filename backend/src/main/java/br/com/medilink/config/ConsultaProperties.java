package br.com.medilink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Prazos da consulta (T17). Pontos de partida técnicos: a decisão final é do produto. */
@ConfigurationProperties(prefix = "medilink.consulta")
public record ConsultaProperties(
		/** Depois de encerrada (por engano ou queda), o médico pode reabrir dentro deste prazo; a transcrição só é enviada depois dele. */
		@DefaultValue("10m") Duration toleranciaReabertura,
		/** Consulta em andamento sem sinal do médico por este tempo é encerrada pelo servidor. */
		@DefaultValue("5m") Duration inatividadeDoMedico,
		/** Quanto antes do horário agendado o médico já pode iniciar a sala. */
		@DefaultValue("30m") Duration janelaAntes,
		/** Até quando depois do horário agendado o médico ainda pode iniciar a sala. */
		@DefaultValue("12h") Duration janelaDepois,
		/** O link do paciente vale até o horário agendado mais este prazo (se a consulta ainda não começou). */
		@DefaultValue("24h") Duration validadeDoLinkDoPaciente,
		/** Vida do link de uso único do médico até ser trocado pela sessão. */
		@DefaultValue("5m") Duration validadeDoLinkDoMedico,
		/** Vida da sessão do médico aberta pelo link. */
		@DefaultValue("8h") Duration validadeDaSessaoDoMedico,
		/** Liga a rotina de encerramento por inatividade (desligada nos testes). */
		@DefaultValue("true") boolean verificarInatividade) {
}
