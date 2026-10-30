-- T18. Administradores (senha + TOTP) e termo de consentimento versionado no banco.

CREATE TABLE administrador (
    id                  UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    email               NVARCHAR(254)    NOT NULL,
    senha_hash          VARCHAR(100)     NOT NULL,
    -- Segredo TOTP cifrado em repouso; só vira exigência de login depois de confirmado com um código.
    totp_segredo        NVARCHAR(MAX)    NULL,
    totp_confirmado     BIT              NOT NULL,
    totp_ultimo_passo   BIGINT           NULL,
    ativo               BIT              NOT NULL,
    falhas_seguidas     INT              NOT NULL,
    bloqueado_ate       DATETIMEOFFSET   NULL,
    criado_em           DATETIMEOFFSET   NOT NULL,
    CONSTRAINT uk_administrador_email UNIQUE (email)
);

-- Cada versão publicada é imutável (o texto aceito precisa continuar recuperável); alterar o termo é publicar outra versão.
CREATE TABLE termo_consentimento (
    id           UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    versao       VARCHAR(20)      NOT NULL,
    texto        NVARCHAR(MAX)    NOT NULL,
    hash_texto   CHAR(64)         NOT NULL,
    status       VARCHAR(12)      NOT NULL,
    criado_por   NVARCHAR(254)    NOT NULL,
    criado_em    DATETIMEOFFSET   NOT NULL,
    publicado_em DATETIMEOFFSET   NULL,
    CONSTRAINT uk_termo_versao UNIQUE (versao)
);
-- No máximo UM termo vigente.
CREATE UNIQUE INDEX uk_termo_vigente ON termo_consentimento (status) WHERE status = 'VIGENTE';
GO

-- Prova do que foi aceito: além da versão, o hash do texto exibido ao paciente.
ALTER TABLE consulta ADD consentimento_hash CHAR(64) NULL;
