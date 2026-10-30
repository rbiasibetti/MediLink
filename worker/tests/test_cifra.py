import base64
from pathlib import Path

import pytest
from cifra_auxiliar import BLOCO, cifrar
from conftest import fonte_json, job_json

from medilink_worker.cifra import Chaves, ErroDeCifra, decifrar_audio, ler_audio
from medilink_worker.config import Config
from medilink_worker.mensagens import ler_job
from medilink_worker.processador import processar

K1 = bytes([1]) * 32
K2 = bytes([2]) * 32
CHAVES = Chaves({"k1": K1})
CLARO = bytes((i * 7) % 251 for i in range(17000))   # o mesmo padrão do arquivo gerado pelo backend (Java)


def test_le_o_arquivo_gerado_pelo_backend_em_java():
    java = (Path(__file__).parent / "fixtures" / "audio-java.mla").read_bytes()

    assert decifrar_audio(java, CHAVES) == CLARO


def test_ida_e_volta_em_varios_tamanhos():
    for n in (0, 1, BLOCO - 1, BLOCO, BLOCO + 1, 3 * BLOCO):
        claro = bytes(range(256)) * (n // 256) + bytes(n % 256)
        assert decifrar_audio(cifrar(claro, K1), CHAVES) == claro


def test_o_arquivo_cifrado_nao_mostra_o_conteudo():
    assert b"OggS" not in cifrar(b"OggS" + b"OpusHead" * 100, K1)


def test_chave_errada_arquivo_adulterado_truncado_e_reordenado_falham():
    cif = cifrar(CLARO, K1, sal=bytes(16))
    for ruim in (
        cif[:-10],                                   # cortado no meio de um bloco
        cif[:-1] + bytes([cif[-1] ^ 1]),             # bit trocado
        b"XXXX" + cif[4:],                           # sem a magia
        cif[:8],                                     # cabeçalho truncado
    ):
        with pytest.raises(ErroDeCifra):
            decifrar_audio(ruim, CHAVES)
    with pytest.raises(ErroDeCifra):
        decifrar_audio(cif, Chaves({"k1": K2}))      # chave errada
    with pytest.raises(ErroDeCifra):
        decifrar_audio(cif, Chaves({"outra": K1}))   # id desconhecido
    cab = 4 + 1 + 2 + 16 + 4
    bloco = 4 + BLOCO + 16
    trocado = cif[:cab] + cif[cab + bloco:cab + 2 * bloco] + cif[cab:cab + bloco] + cif[cab + 2 * bloco:]
    with pytest.raises(ErroDeCifra):
        decifrar_audio(trocado, CHAVES)


def test_chave_antiga_ainda_le_arquivo_antigo():
    cif = cifrar(CLARO, K1, "k1")
    depois = Chaves({"k2": K2, "k1": K1})

    assert decifrar_audio(cif, depois) == CLARO


def test_sem_cifra_so_passa_com_a_flag_desligada(tmp_path):
    arq = tmp_path / "a.opus"
    arq.write_bytes(b"OggS-claro")

    with pytest.raises(ErroDeCifra):
        ler_audio(arq, CHAVES)
    assert ler_audio(arq, CHAVES, exigir_cifra=False) == b"OggS-claro"


def test_chaves_do_ambiente():
    b64 = lambda k: base64.b64encode(k).decode()  # noqa: E731
    ch = Chaves.de_ambiente({"MEDILINK_DATA_KEY": b64(K2), "MEDILINK_DATA_KEY_ID": "k2",
                             "MEDILINK_DATA_KEYS_ANTERIORES": f"k1:{b64(K1)}"})

    assert ch.por_id == {"k2": K2, "k1": K1}
    assert Chaves.de_ambiente({}).por_id == {}
    with pytest.raises(ErroDeCifra):
        Chaves.de_ambiente({"MEDILINK_DATA_KEY": b64(b"curta")})


class _Transcritor:
    def __init__(self):
        self.visto = None

    def transcrever(self, audio, idioma=None):
        from medilink_worker.transcritor import Transcricao
        self.visto = audio.read()
        return Transcricao([(0.0, 1.0, "ok")], 1.0, "pt", 1.0)


def test_processador_decifra_em_memoria_e_exige_cifra_por_padrao(tmp_path):
    (tmp_path / "c-1").mkdir()
    (tmp_path / "c-1" / "m.opus.enc").write_bytes(cifrar(b"OggS-audio", K1))
    (tmp_path / "c-1" / "claro.opus").write_bytes(b"OggS-sem-cifra")
    job = ler_job(job_json([fonte_json("c-1/m.opus.enc", "MEDICO", audio="a1"),
                            fonte_json("c-1/claro.opus", "PACIENTE", audio="a2")]))
    t = _Transcritor()

    import json
    r = json.loads(processar(job, t, Config(pasta_audio=tmp_path, chaves=CHAVES)))

    assert t.visto == b"OggS-audio"                       # o transcritor recebeu o áudio decifrado
    assert [f["papel"] for f in r["fontes"]] == ["MEDICO"]  # o arquivo sem cifra foi recusado


def test_kafka_em_texto_puro_so_em_dev_e_com_tls_exige_a_ca():
    assert Config().kafka_base() == {"bootstrap.servers": Config().kafka}
    tls = Config(kafka_protocolo="SSL", kafka_ca="/etc/medilink/ca.pem").kafka_base()
    assert tls["security.protocol"] == "SSL" and tls["ssl.ca.location"] == "/etc/medilink/ca.pem"
    assert tls["enable.ssl.certificate.verification"] is True
    with pytest.raises(ValueError):
        Config(kafka_protocolo="SSL").kafka_base()   # TLS sem CA não verifica nada: recusa
