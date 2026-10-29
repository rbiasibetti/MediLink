-- Tentativas FALHAS de confirmação de CPF por agendamento (T06). Guarda só o instante: nunca o CPF informado.
-- Em banco (e não em memória) para o limite valer com mais de uma instância da aplicação.
CREATE TABLE tentativa_cpf (
    id             UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
    agendamento_id UNIQUEIDENTIFIER NOT NULL,
    momento        DATETIMEOFFSET   NOT NULL,
    CONSTRAINT fk_tentativa_cpf_agendamento FOREIGN KEY (agendamento_id) REFERENCES agendamento (id)
);
CREATE INDEX ix_tentativa_cpf_agendamento ON tentativa_cpf (agendamento_id, momento);
