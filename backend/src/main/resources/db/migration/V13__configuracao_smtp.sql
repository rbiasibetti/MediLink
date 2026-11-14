-- Configuração do servidor SMTP pela área administrativa (antes só por variáveis de ambiente na subida).
-- Uma única linha: a chave primária é fixa (id constante) e um CHECK garante que não haja outra.

CREATE TABLE configuracao_smtp (
    id              UNIQUEIDENTIFIER NOT NULL PRIMARY KEY
                        CONSTRAINT df_smtp_id DEFAULT '00000000-0000-0000-0000-000000000001'
                        CONSTRAINT ck_smtp_linha_unica CHECK (id = '00000000-0000-0000-0000-000000000001'),
    host            NVARCHAR(255)    NOT NULL,
    porta           INT              NOT NULL,
    usuario         NVARCHAR(255)    NULL,
    -- Senha do relay, cifrada em repouso (mesmo esquema do segredo TOTP do administrador).
    senha_cifrada   NVARCHAR(MAX)    NULL,
    autenticar      BIT              NOT NULL,
    starttls        BIT              NOT NULL,
    remetente       NVARCHAR(255)    NOT NULL,
    atualizado_por  NVARCHAR(254)    NOT NULL,
    atualizado_em   DATETIMEOFFSET   NOT NULL
);
