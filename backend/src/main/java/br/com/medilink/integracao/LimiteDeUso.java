package br.com.medilink.integracao;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de requisições por API key (janela deslizante de 1 minuto, em memória). Com mais de uma instância do backend
 * o limite efetivo é por instância; para o objetivo (conter abuso e laço de repetição de um cliente) basta, e um
 * limitador distribuído fica para quando houver mais de uma instância.
 */
@Component
public class LimiteDeUso {

	private final Map<String, Deque<Long>> janelas = new ConcurrentHashMap<>();
	private final Clock clock;
	private final IntegracaoProperties props;

	public LimiteDeUso(Clock clock, IntegracaoProperties props) {
		this.clock = clock;
		this.props = props;
	}

	/** @return {@code true} se a chamada cabe no limite (e já a registra) */
	public boolean permitir(UUID keyId) {
		return permitir("req:" + keyId, props.limitePorMinuto());
	}

	/** Limite próprio para e-mails disparados pela API: a plataforma não pode virar remetente de spam. */
	public boolean permitirEmail(UUID keyId) {
		return permitir("mail:" + keyId, props.emailsPorMinuto());
	}

	private boolean permitir(String chave, int limite) {
		long agora = clock.millis();
		Deque<Long> fila = janelas.computeIfAbsent(chave, k -> new ArrayDeque<>());
		synchronized (fila) {
			while (!fila.isEmpty() && fila.peekFirst() <= agora - 60_000) {
				fila.pollFirst();
			}
			if (fila.size() >= limite) {
				return false;
			}
			fila.addLast(agora);
			return true;
		}
	}
}
