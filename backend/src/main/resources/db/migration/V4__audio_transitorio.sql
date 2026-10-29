-- Arquivos de áudio transitórios da captura no servidor (T11). Cada linha é um arquivo Ogg Opus de UMA fonte
-- (um participante) da consulta. O áudio é apagado depois que a transcrição é gravada (T13); a linha fica
-- como registro de que existiu e quando foi descartado. O conteúdo do áudio nunca vai para o banco.
CREATE TABLE audio_transitorio (
    id            UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    consulta_id   UNIQUEIDENTIFIER NOT NULL,
    fonte         VARCHAR(100)     NOT NULL,
    endpoint_id   VARCHAR(64)      NULL,
    arquivo       VARCHAR(300)     NOT NULL,
    status        VARCHAR(20)      NOT NULL,
    iniciado_em   DATETIMEOFFSET   NOT NULL,
    finalizado_em DATETIMEOFFSET   NULL,
    amostras      BIGINT           NOT NULL,
    CONSTRAINT fk_audio_transitorio_consulta FOREIGN KEY (consulta_id) REFERENCES consulta (id)
);
CREATE INDEX ix_audio_transitorio_consulta ON audio_transitorio (consulta_id);
