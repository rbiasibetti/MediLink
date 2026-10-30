import json

import pytest

from conftest import fonte_json, job_json
from medilink_worker.config import Config
from medilink_worker.consumidor import Consumidor
from medilink_worker.transcritor import Transcricao


class ProdutorFalso:
    def __init__(self, pendentes=0):
        self.entregues, self.pendentes = [], pendentes

    def produce(self, topico, key, value):
        self.entregues.append((topico, key, json.loads(value)))

    def flush(self, timeout):
        return self.pendentes


class Falso:
    def __init__(self, erro=False):
        self.erro, self.n = erro, 0

    def transcrever(self, arquivo, idioma=None):
        self.n += 1
        if self.erro:
            raise MemoryError("sem memória")
        return Transcricao([(0, 1, "ok")], 1.0, "pt", 1.0)


def _novo(tmp_path, transcritor, produtor):
    # Sem abrir conexão com o Kafka: só a lógica de tratar uma mensagem.
    c = object.__new__(Consumidor)
    c.cfg = Config(pasta_audio=tmp_path, exigir_audio_cifrado=False)
    c.transcritor, c.produtor = transcritor, produtor
    from collections import OrderedDict
    c.vistos, c.parar = OrderedDict(), False
    (tmp_path / "c-1").mkdir()
    (tmp_path / "c-1" / "m.opus").write_bytes(b"x")
    return c


def _job(job_id="job-1"):
    return job_json([fonte_json("c-1/m.opus")], job_id=job_id).encode()


def test_publica_o_resultado_na_chave_da_consulta(tmp_path):
    prod = ProdutorFalso()
    c = _novo(tmp_path, Falso(), prod)

    c._tratar(_job())

    assert prod.entregues[0][0] == "medilink.transcricao.resultados" and prod.entregues[0][1] == "c-1"
    assert prod.entregues[0][2]["status"] == "CONCLUIDO"


def test_job_repetido_nao_e_reprocessado(tmp_path):
    prod, t = ProdutorFalso(), Falso()
    c = _novo(tmp_path, t, prod)

    c._tratar(_job())
    c._tratar(_job())

    assert t.n == 1 and len(prod.entregues) == 1


def test_job_fora_do_contrato_e_descartado_sem_travar(tmp_path):
    prod = ProdutorFalso()
    c = _novo(tmp_path, Falso(), prod)

    c._tratar(b"lixo")

    assert prod.entregues == []


def test_erro_inesperado_vira_resultado_de_falha_com_so_o_tipo(tmp_path):
    prod = ProdutorFalso()
    c = _novo(tmp_path, Falso(erro=True), prod)

    c._tratar(_job())

    r = prod.entregues[0][2]
    # MemoryError não é um erro de leitura de fonte: sobe até o laço, que publica FALHA só com o tipo (o áudio é mantido).
    assert r["status"] == "FALHA" and r["erro"] == "MemoryError"


def test_se_o_resultado_nao_chega_ao_broker_o_job_nao_e_dado_como_visto(tmp_path):
    c = _novo(tmp_path, Falso(), ProdutorFalso(pendentes=1))

    with pytest.raises(RuntimeError):
        c._tratar(_job())

    assert "job-1" not in c.vistos  # será reprocessado: a mensagem do job não foi confirmada
