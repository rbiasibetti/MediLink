-- T12. Quem é quem na consulta (para rotular as falas) e o job de transcrição de cada consulta.

-- Endpoint do Jitsi (o "endpointId" que o JVB informa ao audio sink) de cada participante, com o papel dele.
CREATE TABLE consulta_participante (
    id            UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    consulta_id   UNIQUEIDENTIFIER NOT NULL,
    endpoint_id   VARCHAR(64)      NOT NULL,
    papel         VARCHAR(20)      NOT NULL,
    registrado_em DATETIMEOFFSET   NOT NULL,
    CONSTRAINT uk_consulta_participante UNIQUE (consulta_id, endpoint_id),
    CONSTRAINT fk_consulta_participante_consulta FOREIGN KEY (consulta_id) REFERENCES consulta (id)
);

-- Um job por consulta. Só guarda o estado do envio à fila; o resultado da transcrição é da T13.
CREATE TABLE transcricao_job (
    id            UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    consulta_id   UNIQUEIDENTIFIER NOT NULL,
    status        VARCHAR(20)      NOT NULL,
    tentativas    INT              NOT NULL,
    criado_em     DATETIMEOFFSET   NOT NULL,
    atualizado_em DATETIMEOFFSET   NOT NULL,
    ultimo_erro   VARCHAR(200)     NULL,
    CONSTRAINT uk_transcricao_job_consulta UNIQUE (consulta_id),
    CONSTRAINT fk_transcricao_job_consulta FOREIGN KEY (consulta_id) REFERENCES consulta (id)
);
