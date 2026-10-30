import json
import logging

from conftest import fonte_json, job_json
from medilink_worker.config import Config
from medilink_worker.mensagens import ler_job
from medilink_worker.processador import processar
from medilink_worker.transcritor import Transcricao


class Falso:
    """Transcritor falso: devolve falas roteirizadas pelo nome, que o teste grava como conteúdo do arquivo."""

    def __init__(self, roteiro, erro_em=()):
        self.roteiro, self.erro_em, self.chamadas = roteiro, set(erro_em), []

    def transcrever(self, arquivo, idioma=None):
        nome = arquivo.read().decode()
        self.chamadas.append((nome, idioma))
        if nome in self.erro_em:
            raise RuntimeError("falha de decodificação com texto SECRETO da consulta")
        return Transcricao(self.roteiro[nome], 10.0, "pt", 0.99)


def _cfg(pasta):
    return Config(pasta_audio=pasta, exigir_audio_cifrado=False)  # a cifra tem testes próprios (test_cifra.py)


def _audios(pasta, *nomes):
    (pasta / "c-1").mkdir(exist_ok=True)
    for n in nomes:
        (pasta / "c-1" / n).write_bytes(n.encode())


def test_transcreve_as_duas_fontes_e_rotula_o_locutor(tmp_path):
    _audios(tmp_path, "m.opus", "p.opus")
    job = ler_job(job_json([
        fonte_json("c-1/m.opus", "MEDICO", "2026-10-29T12:00:00Z", "a1"),
        fonte_json("c-1/p.opus", "PACIENTE", "2026-10-29T12:00:02Z", "a2")]))
    falso = Falso({"m.opus": [(0.0, 1.5, "Boa tarde.")], "p.opus": [(0.2, 1.0, "Boa tarde, doutora.")]})

    r = json.loads(processar(job, falso, _cfg(tmp_path)))

    assert r["status"] == "CONCLUIDO" and r["jobId"] == "job-1" and r["consultaId"] == "c-1" and r["idioma"] == "pt"
    assert [(s["papel"], s["inicioMs"], s["texto"]) for s in r["segmentos"]] == [
        ("MEDICO", 0, "Boa tarde."), ("PACIENTE", 2200, "Boa tarde, doutora.")]
    assert {f["papel"]: f["falas"] for f in r["fontes"]} == {"MEDICO": 1, "PACIENTE": 1}
    assert all(c[1] == "pt" for c in falso.chamadas)


def test_uma_fonte_ausente_nao_derruba_as_outras(tmp_path):
    _audios(tmp_path, "m.opus")
    job = ler_job(job_json([fonte_json("c-1/m.opus", "MEDICO"), fonte_json("c-1/some.opus", "PACIENTE", audio="a2")]))

    r = json.loads(processar(job, Falso({"m.opus": [(0, 1, "Olá.")]}), _cfg(tmp_path)))

    assert r["status"] == "CONCLUIDO" and len(r["fontes"]) == 1 and r["segmentos"][0]["texto"] == "Olá."


def test_sem_nenhuma_fonte_legivel_o_resultado_e_falha_sem_vazar_texto(tmp_path):
    job = ler_job(job_json([fonte_json("c-1/some.opus")]))

    r = json.loads(processar(job, Falso({}), _cfg(tmp_path)))

    assert r["status"] == "FALHA" and r["erro"] == "NenhumaFonteLegivel" and r["segmentos"] == []


def test_erro_do_transcritor_nao_vaza_a_mensagem_para_o_log_nem_para_o_resultado(tmp_path, caplog):
    _audios(tmp_path, "m.opus")
    job = ler_job(job_json([fonte_json("c-1/m.opus")]))

    with caplog.at_level(logging.DEBUG):
        saida = processar(job, Falso({}, erro_em={"m.opus"}), _cfg(tmp_path))

    assert "SECRETO" not in saida and "SECRETO" not in caplog.text
    assert json.loads(saida)["status"] == "FALHA"


def test_caminho_malicioso_no_job_e_ignorado(tmp_path):
    job = ler_job(job_json([fonte_json("../../etc/passwd")]))

    r = json.loads(processar(job, Falso({}), _cfg(tmp_path)))

    assert r["status"] == "FALHA"


def test_o_texto_da_consulta_nunca_vai_para_o_log(tmp_path, caplog):
    _audios(tmp_path, "m.opus")
    job = ler_job(job_json([fonte_json("c-1/m.opus")]))

    with caplog.at_level(logging.DEBUG):
        processar(job, Falso({"m.opus": [(0, 1, "O paciente relata dor torácica.")]}), _cfg(tmp_path))

    assert "torácica" not in caplog.text
