-- T20. Login do médico por usuário (e-mail) e senha; o TOTP é habilitado, caso a caso, pelo administrador.
-- Médicos que já existem (criados por sistemas integrados) ficam sem senha: só entram pelo link de uso único.

ALTER TABLE medico ADD
    senha_hash        VARCHAR(100)   NULL,
    ativo             BIT            NOT NULL CONSTRAINT df_medico_ativo DEFAULT 1,
    -- Decisão do administrador: com TOTP habilitado, o login exige o código do aplicativo autenticador.
    totp_habilitado   BIT            NOT NULL CONSTRAINT df_medico_totp_habilitado DEFAULT 0,
    totp_segredo      NVARCHAR(MAX)  NULL,
    totp_confirmado   BIT            NOT NULL CONSTRAINT df_medico_totp_confirmado DEFAULT 0,
    totp_ultimo_passo BIGINT         NULL,
    falhas_seguidas   INT            NOT NULL CONSTRAINT df_medico_falhas DEFAULT 0,
    bloqueado_ate     DATETIMEOFFSET NULL;
