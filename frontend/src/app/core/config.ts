/** Host do Jitsi self-hosted (infra/jitsi). Em dev usa certificado autoassinado: abra-o uma vez no navegador. */
export const JITSI_DOMAIN = 'localhost:8443';

/** Intervalo de consulta ao backend enquanto se espera o aceite do médico. */
export const INTERVALO_ESPERA_MS = 3000;

/**
 * "Definições de desempenho" do Jitsi, nível 2 de 0 a 3: 0 melhor desempenho, 1 baixa definição (LD),
 * 2 definição padrão (SD), 3 alta definição (HD). O nível 2 é a definição padrão: 360 de altura.
 */
export const ALTURA_VIDEO_NIVEL_2 = 360;

/**
 * Criptografia de ponta a ponta (E2EE) do Jitsi ao entrar na sala. **Desligada por padrão**: a transcrição
 * precisa do áudio decifrado no servidor (T11), e com E2EE o JVB só vê quadros cifrados. Decisão e condições
 * em docs/plan.md ("Decisão: E2EE desligada"). Para ligar, mude para true: o médico (moderador) passa a
 * chamar toggleE2EE(true) ao entrar; a captura pelo servidor deixa de funcionar.
 */
export const E2EE_ATIVA_POR_PADRAO = false;
