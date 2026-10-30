package br.com.medilink.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * No perfil {@code prod} a aplicação <strong>não sobe</strong> com tráfego interno sem TLS verificado ou com a
 * migração de texto legado ligada (T14). É a forma de a condição "tráfego interno cifrado" da decisão sobre a E2EE
 * (docs/plan.md) valer na prática, e não só no papel: configuração insegura vira erro de inicialização.
 * As mensagens citam só os nomes das propriedades, nunca os valores (a URL do banco pode ter credencial).
 */
@Component
@Profile("prod")
class ExigenciasDeProducao implements InitializingBean {

	private final Environment env;

	ExigenciasDeProducao(Environment env) {
		this.env = env;
	}

	@Override
	public void afterPropertiesSet() {
		List<String> problemas = violacoes(env);
		if (!problemas.isEmpty()) {
			throw new IllegalStateException("Configuração insegura para produção:\n - " + String.join("\n - ", problemas));
		}
	}

	static List<String> violacoes(Environment env) {
		List<String> p = new ArrayList<>();
		String banco = env.getProperty("spring.datasource.url", "").toLowerCase(Locale.ROOT).replace(" ", "");
		if (!banco.contains("encrypt=true")) {
			p.add("spring.datasource.url precisa ter encrypt=true (TLS até o SQL Server)");
		}
		if (banco.contains("trustservercertificate=true")) {
			p.add("spring.datasource.url não pode ter trustServerCertificate=true (o certificado do banco precisa ser verificado)");
		}
		if (!env.getProperty("server.ssl.enabled", Boolean.class, false)) {
			p.add("server.ssl.enabled precisa ser true (HTTPS/WSS até o backend, inclusive o audio sink)");
		}
		String kafka = env.getProperty("spring.kafka.security.protocol", "PLAINTEXT").toUpperCase(Locale.ROOT);
		if (!kafka.equals("SSL") && !kafka.equals("SASL_SSL")) {
			p.add("spring.kafka.security.protocol precisa ser SSL ou SASL_SSL (a fila carrega o texto das consultas)");
		}
		if (env.getProperty("medilink.crypto.aceitar-texto-legado", Boolean.class, false)) {
			p.add("medilink.crypto.aceitar-texto-legado precisa ficar desligado (só serve para migrar uma base antiga)");
		}
		return p;
	}
}
