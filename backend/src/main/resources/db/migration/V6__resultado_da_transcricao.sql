-- T13. O resultado do worker vira registro da consulta.
-- "texto" segue sendo o texto corrido (falas rotuladas por papel); "segmentos" guarda as mesmas falas com o tempo
-- de cada uma (JSON), que é o que a API devolve. Ambos são dado sensível de saúde (cifragem em repouso: T14).
ALTER TABLE transcricao ADD
    segmentos  NVARCHAR(MAX) NOT NULL CONSTRAINT df_transcricao_segmentos DEFAULT '[]',
    duracao_ms BIGINT        NOT NULL CONSTRAINT df_transcricao_duracao DEFAULT 0;
