#!/usr/bin/env python3
"""T13 ponta a ponta: da fala em português pelo Jitsi real até a transcrição gravada no banco, lida pela API, e o áudio apagado.

Fluxo: igual ao da T12 (médico e paciente "falam" trechos diferentes; o sink captura; a consulta é encerrada; o worker
transcreve), mas aqui quem consome o resultado é o próprio backend (T13). O script só enxerga o que a API mostra:
GET /api/consultas/{id}/transcricao (202 enquanto processa, depois 200 com os falantes) e o diretório de áudio do sink,
que deve ficar sem a pasta da consulta.

Pré-requisitos: os da T12 (Jitsi, Redpanda, worker rodando, porta 4200 livre, Chrome) e o backend em dev em :8090
com o código da T13 (ele consome o tópico de resultados). O diretório de áudio é o padrão do backend
(<tmp>/medilink-audio); informe MEDILINK_SINK_DIR se mudou.

Uso: verify_t13.py <usuario:senha-do-medico> <fala-medico.wav> <fala-paciente.wav>
"""
import functools
import http.server
import os
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_t11 as base  # noqa: E402
import verify_t12 as t12  # noqa: E402

SINK = Path(os.environ.get("MEDILINK_SINK_DIR") or Path(tempfile.gettempdir()) / "medilink-audio")


def esperar_transcricao(consulta, auth, limite_s):
    """(status http, corpo, status vistos): repete o GET até sair do 202."""
    vistos = []
    fim = time.time() + limite_s
    while time.time() < fim:
        st, corpo = base.api(f"/api/consultas/{consulta}/transcricao", None, auth, metodo="GET")
        if not vistos or vistos[-1] != st:
            vistos.append(st)
        if st != 202 and st != 409:
            return st, corpo, vistos
        time.sleep(2)
    return None, {}, vistos


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    auth, wav_m, wav_p = sys.argv[1:4]

    class Silencioso(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a, **k):
            pass

    aqui = Path(__file__).resolve().parent
    servidor = http.server.ThreadingHTTPServer(("127.0.0.1", 4200), functools.partial(Silencioso, directory=str(aqui)))
    threading.Thread(target=servidor.serve_forever, daemon=True).start()
    try:
        consulta = t12.cenario(auth, "A: com consentimento", True, wav_m, wav_p, None)
        print("aguardando o worker e o backend gravarem a transcrição…", flush=True)
        st, t, vistos = esperar_transcricao(consulta, auth, 240)
        base.conferir("a API entregou a transcrição (200)", st == 200, f"respostas vistas: {vistos}")
        if st == 200:
            falas = {"MEDICO": [], "PACIENTE": []}
            for s in t["segmentos"]:
                falas.setdefault(s["papel"], []).append(s)
            texto = {p: " ".join(s["texto"] for s in v).lower() for p, v in falas.items()}
            base.conferir("idioma pt e duração coerente", t["idioma"] == "pt" and t["duracaoMs"] > 20_000, f"{t['idioma']} / {t['duracaoMs']} ms")
            base.conferir("a fala do MÉDICO está no registro, como médico",
                          "república federativa" in texto["MEDICO"] and "presidencialista" in texto["MEDICO"], texto["MEDICO"][:100])
            base.conferir("a fala do PACIENTE está no registro, como paciente",
                          any(p in texto["PACIENTE"] for p in ("venezuela", "guiana", "suriname", "uruguai")), texto["PACIENTE"][:100])
            base.conferir("rótulos não trocados", "venezuela" not in texto["MEDICO"] and "república federativa" not in texto["PACIENTE"])
            base.conferir("o texto corrido traz os dois rótulos", "Médico: " in t["texto"] and "Paciente: " in t["texto"])
            ordem = [s["inicioMs"] for s in t["segmentos"]]
            base.conferir("segmentos em ordem de tempo", ordem == sorted(ordem))
        # O descarte vem logo depois da gravação; dá alguns segundos de folga.
        fim = time.time() + 20
        while (SINK / consulta).exists() and time.time() < fim:
            time.sleep(1)
        base.conferir("o áudio da consulta foi apagado do disco (pasta removida)", not (SINK / consulta).exists(), str(SINK / consulta))
        st2, _ = base.api(f"/api/consultas/{consulta}/transcricao", None, auth, metodo="GET")
        base.conferir("a transcrição continua disponível depois do descarte", st2 == 200)

        consulta_b = t12.cenario(auth, "B: sem consentimento", False, wav_m, wav_p, None)
        print("aguardando o backend concluir o job sem áudio…", flush=True)
        stb, _, vistos_b = esperar_transcricao(consulta_b, auth, 90)
        base.conferir("sem consentimento não há transcrição (404)", stb == 404, f"respostas vistas: {vistos_b}")
        base.conferir("e não ficou pasta de áudio", not (SINK / consulta_b).exists())
    finally:
        servidor.shutdown()
    sys.exit(1 if base.falhas else 0)


if __name__ == "__main__":
    main()
