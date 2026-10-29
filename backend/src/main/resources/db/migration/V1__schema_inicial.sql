-- SQL Server 2019+ (produção) e H2 em modo MSSQLServer (testes rápidos). Mantenha esta migração
-- compatível com os dois; a validação definitiva exige uma instância SQL Server 2019+ real.
-- Texto em NVARCHAR: nomes e transcrições em português precisam de Unicode, independente da collation.

CREATE TABLE medico (
    id                         UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    nome                       NVARCHAR(200)    NOT NULL,
    identificador_profissional NVARCHAR(50)     NOT NULL,
    email                      NVARCHAR(254)    NOT NULL,
    CONSTRAINT uk_medico_email UNIQUE (email)
);

CREATE TABLE agendamento (
    id                         UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    medico_id                  UNIQUEIDENTIFIER NOT NULL,
    paciente_nome              NVARCHAR(200)    NOT NULL,
    -- HMAC-SHA256 do CPF em hex (64 chars); o CPF em claro nunca é persistido.
    paciente_cpf_hmac          VARCHAR(64)      NOT NULL,
    paciente_email             NVARCHAR(254)    NULL,
    data_hora                  DATETIMEOFFSET   NOT NULL,
    room_name                  VARCHAR(64)      NOT NULL,
    cpf_validation_enabled     BIT              NOT NULL,
    status                     VARCHAR(20)      NOT NULL,
    criado_em                  DATETIMEOFFSET   NOT NULL,
    CONSTRAINT uk_agendamento_room UNIQUE (room_name),
    CONSTRAINT fk_agendamento_medico FOREIGN KEY (medico_id) REFERENCES medico (id)
);
CREATE INDEX ix_agendamento_medico_data ON agendamento (medico_id, data_hora);

CREATE TABLE consulta (
    id                         UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    agendamento_id             UNIQUEIDENTIFIER NOT NULL,
    inicio_em                  DATETIMEOFFSET   NULL,
    fim_em                     DATETIMEOFFSET   NULL,
    consentimento_em           DATETIMEOFFSET   NULL,
    status                     VARCHAR(30)      NOT NULL,
    CONSTRAINT uk_consulta_agendamento UNIQUE (agendamento_id),
    CONSTRAINT fk_consulta_agendamento FOREIGN KEY (agendamento_id) REFERENCES agendamento (id)
);

CREATE TABLE transcricao (
    id                         UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    consulta_id                UNIQUEIDENTIFIER NOT NULL,
    texto                      NVARCHAR(MAX)    NOT NULL,
    idioma                     VARCHAR(10)      NOT NULL,
    criada_em                  DATETIMEOFFSET   NOT NULL,
    -- Uma transcrição por consulta: base da idempotência do descarte (T13).
    CONSTRAINT uk_transcricao_consulta UNIQUE (consulta_id),
    CONSTRAINT fk_transcricao_consulta FOREIGN KEY (consulta_id) REFERENCES consulta (id)
);

CREATE TABLE audit_log (
    id                         UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    ator                       NVARCHAR(254)    NOT NULL,
    acao                       VARCHAR(60)      NOT NULL,
    recurso                    VARCHAR(200)     NOT NULL,
    momento                    DATETIMEOFFSET   NOT NULL
);
CREATE INDEX ix_audit_log_recurso ON audit_log (recurso, momento);
