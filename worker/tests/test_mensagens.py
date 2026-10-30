import os

import pytest

from conftest import fonte_json, job_json
from medilink_worker.mensagens import JobInvalido, caminho_seguro, ler_job


def test_le_um_job_valido_e_converte_o_horario_para_epoch_ms():
    job = ler_job(job_json([fonte_json(papel="PACIENTE", iniciado="2026-10-29T12:00:01.500Z")]))

    assert (job.job_id, job.consulta_id, job.idioma) == ("job-1", "c-1", "pt")
    assert job.fontes[0].papel == "PACIENTE"
    assert job.fontes[0].iniciado_em_ms == 1793275201500


def test_papel_desconhecido_vira_DESCONHECIDO():
    assert ler_job(job_json([fonte_json(papel="ENFERMEIRO")])).fontes[0].papel == "DESCONHECIDO"


@pytest.mark.parametrize("bruto", ["isto não é json", "{}", job_json([], versao=2), job_json([{"audioId": "x"}]), "[]", "null"])
def test_job_fora_do_contrato_e_recusado_sem_vazar_o_conteudo(bruto):
    with pytest.raises(JobInvalido) as e:
        ler_job(bruto)
    assert bruto[:20] not in str(e.value) or bruto[:20] == ""


def test_caminho_seguro_aceita_arquivo_dentro_da_pasta(tmp_path):
    (tmp_path / "c-1").mkdir()
    (tmp_path / "c-1" / "a.opus").write_bytes(b"x")

    assert caminho_seguro(tmp_path, "c-1/a.opus") == (tmp_path / "c-1" / "a.opus").resolve()


@pytest.mark.parametrize("ruim", ["../x.opus", "/etc/passwd", "c-1/../../x", "", "a/../../../etc/passwd"])
def test_caminho_seguro_recusa_traversal_e_absolutos(tmp_path, ruim):
    with pytest.raises(JobInvalido):
        caminho_seguro(tmp_path, ruim)


def test_caminho_seguro_recusa_link_simbolico_que_sai_da_pasta(tmp_path):
    fora = tmp_path.parent / "segredo.txt"
    fora.write_text("não deve ser lido")
    pasta = tmp_path / "sink"
    (pasta / "c-1").mkdir(parents=True)
    os.symlink(fora, pasta / "c-1" / "link.opus")

    with pytest.raises(JobInvalido):
        caminho_seguro(pasta, "c-1/link.opus")
