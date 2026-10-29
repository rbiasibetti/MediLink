package br.com.medilink.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

	/**
	 * API stateless. Rotas de paciente (/api/salas/**) são públicas e se protegem por CPF + token de sala
	 * (T06/T07); o restante exige médico autenticado. O mecanismo de login definitivo é decisão aberta
	 * (docs/plan.md); HTTP Basic é o provisório.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/api/salas/**").permitAll()
						// O audio sink se autentica no handshake (Bearer do Jicofo); ver AudioSinkHandshake.
						.requestMatchers("/api/audio-sink/**").permitAll()
						// Consentimento é do paciente: ele se identifica pelo token de entrada, no corpo.
						.requestMatchers(HttpMethod.GET, "/api/termo-consentimento").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/consultas/*/consentimento").permitAll()
						.requestMatchers("/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
	}
}
