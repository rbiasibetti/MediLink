-- T16. Sistemas integrados, suas API keys e o vínculo dos agendamentos/médicos com eles.

CREATE TABLE integrador (
    id              UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    nome            NVARCHAR(200)    NOT NULL,
    ativo           BIT              NOT NULL,
    -- Webhook de devolução (T19): URL base HTTPS e segredo de assinatura (cifrado; só o backend o lê).
    webhook_base_url NVARCHAR(500)   NULL,
    webhook_segredo  NVARCHAR(MAX)   NULL,
    webhook_ativo    BIT             NOT NULL CONSTRAINT df_integrador_webhook DEFAULT 0,
    criado_em       DATETIMEOFFSET   NOT NULL,
    CONSTRAINT uk_integrador_nome UNIQUE (nome)
);

-- A key completa (mlk_<id>_<segredo>) é mostrada uma única vez; aqui fica só o SHA-256 do segredo.
CREATE TABLE api_key (
    id            UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    integrador_id UNIQUEIDENTIFIER NOT NULL,
    nome          NVARCHAR(100)    NOT NULL,
    hash_segredo  CHAR(64)         NOT NULL,
    escopos       VARCHAR(200)     NOT NULL,
    ativa         BIT              NOT NULL,
    criada_em     DATETIMEOFFSET   NOT NULL,
    expira_em     DATETIMEOFFSET   NULL,
    ultimo_uso_em DATETIMEOFFSET   NULL,
    revogada_em   DATETIMEOFFSET   NULL,
    CONSTRAINT fk_api_key_integrador FOREIGN KEY (integrador_id) REFERENCES integrador (id)
);
CREATE INDEX ix_api_key_integrador ON api_key (integrador_id);

-- Médicos criados por um sistema integrado: a chave de identificação é única dentro do sistema.
ALTER TABLE medico ADD integrador_id UNIQUEIDENTIFIER NULL, chave_externa NVARCHAR(50) NULL;
GO
ALTER TABLE medico ADD CONSTRAINT fk_medico_integrador FOREIGN KEY (integrador_id) REFERENCES integrador (id);
CREATE UNIQUE INDEX uk_medico_integrador_chave ON medico (integrador_id, chave_externa) WHERE integrador_id IS NOT NULL;

-- Agendamentos criados por API. CPF passa a ser opcional (sem CPF, a validação fica desligada) e nome/e-mail do
-- paciente passam a ser cifrados em repouso (o texto cifrado é maior que o claro, daí o alargamento das colunas).
ALTER TABLE agendamento ADD integrador_id UNIQUEIDENTIFIER NULL, referencia_externa NVARCHAR(100) NULL,
    webhook_destino NVARCHAR(MAX) NULL;
GO
ALTER TABLE agendamento ADD CONSTRAINT fk_agendamento_integrador FOREIGN KEY (integrador_id) REFERENCES integrador (id);
CREATE UNIQUE INDEX uk_agendamento_referencia ON agendamento (integrador_id, referencia_externa) WHERE referencia_externa IS NOT NULL;
ALTER TABLE agendamento ALTER COLUMN paciente_cpf_hmac VARCHAR(64) NULL;
ALTER TABLE agendamento ALTER COLUMN paciente_nome NVARCHAR(1200) NOT NULL;
ALTER TABLE agendamento ALTER COLUMN paciente_email NVARCHAR(1200) NULL;
