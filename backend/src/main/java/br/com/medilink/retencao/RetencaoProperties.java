package br.com.medilink.retencao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Política de retenção (T14). Os prazos são <strong>decisões de negócio e jurídicas</strong>; os valores padrão abaixo
 * são só ponto de partida técnico. {@code transcricao} zerado significa "guardar sem expirar" (a transcrição é dado de
 * saúde cujo prazo de guarda depende do enquadramento no prontuário: definir com o jurídico antes de produção).
 */
@ConfigurationProperties(prefix = "medilink.retencao")
public record RetencaoProperties(
		/** Liga a rotina periódica (desligada nos testes, que chamam os métodos direto). */
		@DefaultValue("true") boolean ativa,
		@DefaultValue("1h") Duration intervalo,
		/** Tentativas falhas de CPF só servem à janela de bloqueio (15 min); guardar mais não tem finalidade. */
		@DefaultValue("24h") Duration tentativaCpf,
		/** Teto de guarda do áudio transitório, mesmo de job que falhou: depois disso o arquivo é apagado sem transcrição. */
		@DefaultValue("7d") Duration audioMaximo,
		/** Prazo para apagar a transcrição; zero (padrão) = guardar. */
		@DefaultValue("0s") Duration transcricao) {
}
