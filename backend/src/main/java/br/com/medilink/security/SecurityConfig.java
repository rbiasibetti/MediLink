package br.com.medilink.security;

import br.com.medilink.admin.AdminFiltro;
import br.com.medilink.integracao.ApiKeyFiltro;
import br.com.medilink.medico.SessaoMedicoFiltro;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

	/** O filtro da API key roda só dentro da cadeia do Spring Security, não também como filtro de servlet. */
	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<ApiKeyFiltro> apiKeyFiltroSemRegistroGlobal(ApiKeyFiltro filtro) {
		var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filtro);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<AdminFiltro> adminFiltroSemRegistroGlobal(AdminFiltro filtro) {
		var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filtro);
		reg.setEnabled(false);
		return reg;
	}

	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<SessaoMedicoFiltro> sessaoMedicoFiltroSemRegistroGlobal(SessaoMedicoFiltro filtro) {
		var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filtro);
		reg.setEnabled(false);
		return reg;
	}

	/**
	 * API stateless. Rotas de paciente (/api/salas/**) são públicas e se protegem por CPF + token de sala
	 * (T06/T07); o restante exige médico autenticado. O mecanismo de login definitivo é decisão aberta
	 * (docs/plan.md); HTTP Basic é o provisório.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, ApiKeyFiltro apiKeyFiltro, SessaoMedicoFiltro sessaoMedicoFiltro, AdminFiltro adminFiltro) throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.addFilterBefore(apiKeyFiltro, org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
				.addFilterBefore(sessaoMedicoFiltro, org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
				.addFilterBefore(adminFiltro, org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						// API de integração (T16): autenticada pela API key no filtro; cada rota exige o seu escopo.
						.requestMatchers(HttpMethod.GET, "/api/integracao/v1/agendamentos/**").hasAuthority("SCOPE_agendamento:ler")
						.requestMatchers("/api/integracao/v1/agendamentos/*/acesso-medico").hasAuthority("SCOPE_sala:iniciar")
						.requestMatchers("/api/integracao/**").hasAuthority("SCOPE_agendamento:escrever")
						.requestMatchers("/api/salas/**").permitAll()
						// O audio sink se autentica no handshake (Bearer do Jicofo); ver AudioSinkHandshake.
						.requestMatchers("/api/audio-sink/**").permitAll()
						// Administração (T18): o login é público; o resto exige a sessão do administrador.
						.requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
						.requestMatchers("/api/admin/**").hasRole("ADMIN")
						// Troca do link de uso único do médico pela sessão da consulta (T17).
						.requestMatchers(HttpMethod.POST, "/api/medico/sessao").permitAll()
						// Consentimento é do paciente: ele se identifica pelo token de entrada, no corpo.
						.requestMatchers(HttpMethod.GET, "/api/termo-consentimento").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/consultas/*/consentimento").permitAll()
						// Participante: o médico entra autenticado, o paciente pelo token de entrada no corpo.
						.requestMatchers(HttpMethod.POST, "/api/consultas/*/participante").permitAll()
						.requestMatchers("/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
	}
}
