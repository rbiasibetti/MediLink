-- T19. Tabela de saída (outbox) do webhook de devolução. Guarda só a REFERÊNCIA: o corpo (que tem o texto da
-- consulta) é montado na hora do envio a partir da transcrição já cifrada, nunca duplicado aqui.
CREATE TABLE webhook_entrega (
    id                    UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    evento_id             UNIQUEIDENTIFIER NOT NULL,
    agendamento_id        UNIQUEIDENTIFIER NOT NULL,
    consulta_id           UNIQUEIDENTIFIER NOT NULL,
    integrador_id         UNIQUEIDENTIFIER NOT NULL,
    evento                VARCHAR(40)      NOT NULL,
    motivo                VARCHAR(40)      NULL,
    status                VARCHAR(12)      NOT NULL,
    tentativas            INT              NOT NULL,
    proxima_tentativa_em  DATETIMEOFFSET   NULL,
    ultimo_status_http    INT              NULL,
    ultimo_erro           VARCHAR(200)     NULL,
    criado_em             DATETIMEOFFSET   NOT NULL,
    entregue_em           DATETIMEOFFSET   NULL,
    CONSTRAINT uk_webhook_evento UNIQUE (consulta_id, evento),
    CONSTRAINT fk_webhook_entrega_integrador FOREIGN KEY (integrador_id) REFERENCES integrador (id)
);
CREATE INDEX ix_webhook_entrega_fila ON webhook_entrega (status, proxima_tentativa_em);
