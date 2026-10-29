-- Versão do termo que o paciente viu ao consentir (T09): prova de QUAL texto foi aceito, não só de quando.
ALTER TABLE consulta ADD consentimento_versao VARCHAR(20) NULL;
