package br.com.medilink.domain;

public enum PapelParticipante {
	MEDICO,
	PACIENTE,
	/** Fonte de áudio sem participante registrado (só aparece nos jobs, nunca no banco). */
	DESCONHECIDO
}
