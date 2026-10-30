-- T17. Link de uso único do médico, presença (encerramento por inatividade) e tolerância para reabrir.

-- Só o hash do token do link; o link inteiro existe apenas na resposta que o sistema integrado recebe.
CREATE TABLE acesso_medico_link (
    id             UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    agendamento_id UNIQUEIDENTIFIER NOT NULL,
    consulta_id    UNIQUEIDENTIFIER NOT NULL,
    medico_id      UNIQUEIDENTIFIER NOT NULL,
    hash_token     CHAR(64)         NOT NULL,
    modo           VARCHAR(10)      NOT NULL,
    criado_em      DATETIMEOFFSET   NOT NULL,
    expira_em      DATETIMEOFFSET   NOT NULL,
    usado_em       DATETIMEOFFSET   NULL,
    revogado_em    DATETIMEOFFSET   NULL,
    CONSTRAINT uk_acesso_medico_hash UNIQUE (hash_token),
    CONSTRAINT fk_acesso_medico_agendamento FOREIGN KEY (agendamento_id) REFERENCES agendamento (id),
    CONSTRAINT fk_acesso_medico_consulta FOREIGN KEY (consulta_id) REFERENCES consulta (id),
    CONSTRAINT fk_acesso_medico_medico FOREIGN KEY (medico_id) REFERENCES medico (id)
);
CREATE INDEX ix_acesso_medico_consulta ON acesso_medico_link (consulta_id);

-- Último sinal de vida do médico na consulta em andamento (a tela dele consulta o estado a cada poucos segundos).
ALTER TABLE consulta ADD ultima_presenca_medico_em DATETIMEOFFSET NULL;
-- O job de transcrição só é publicado depois da janela de tolerância (o médico pode reabrir a consulta).
ALTER TABLE transcricao_job ADD elegivel_em DATETIMEOFFSET NULL;
