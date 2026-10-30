#!/usr/bin/env python3
"""T12 ponta a ponta: fala em português pelo Jitsi real até o texto transcrito no tópico de resultados.

Fluxo: o médico e o paciente "falam" trechos DIFERENTES de um áudio (microfone falso do Chrome de teste); o backend
captura no sink; a consulta é encerrada; o backend enfileira o job; o worker (faster-whisper) transcreve; este script
lê o resultado do Redpanda. Prova que a fala sai em português, com cada trecho rotulado para o locutor certo.

Pré-requisitos (veja o README): Jitsi no ar, backend em dev em :8090, Redpanda (infra/dev/redpanda.sh up), o worker
rodando (worker/.venv/bin/python -m medilink_worker), porta 4200 livre e Chrome. Rode com o Python do venv do worker
(precisa do confluent_kafka).

Uso: verify_t12.py <usuario:senha-do-medico> <fala-medico.wav> <fala-paciente.wav>
Os WAV são mono, 48 kHz, 16 bits; cada um começa com silêncio e depois a fala (o sink só grava depois do aceite).
"""
import functools
import http.server
import json
import re
import shutil
import sys
import tempfile
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

from confluent_kafka import Consumer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_t11 as base  # noqa: E402  (reaproveita a API, o leitor de Ogg e o navegador de teste)

KAFKA = "localhost:9092"
TOPICO_RESULTADOS = "medilink.transcricao.resultados"


def consumidor_de_resultados():
    c = Consumer({"bootstrap.servers": KAFKA, "group.id": "verify-" + uuid.uuid4().hex, "auto.offset.reset": "latest",
                  "enable.auto.commit": False})
    c.subscribe([TOPICO_RESULTADOS])
    c.poll(2.0)  # junta-se ao grupo e recebe as partições antes de o resultado ser publicado
    return c


def esperar_resultado(c, consulta, limite_s):
    fim = time.time() + limite_s
    while time.time() < fim:
        m = c.poll(1.0)
        if m is not None and not m.error() and m.key() and m.key().decode() == consulta:
            return json.loads(m.value())
    return None


def esperar_endpoint(log, limite_s=25):
    fim = time.time() + limite_s
    while time.time() < fim:
        m = re.search(r"HARNESS joined (\w+)", Path(log).read_text(errors="ignore"))
        if m:
            return m.group(1)
        time.sleep(1)
    return None


def cenario(auth, nome, consentir, wav_medico, wav_paciente, consumidor):
    print(f"\n=== {nome} ===", flush=True)
    quando = (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    _, ag = base.api("/api/agendamentos", {"pacienteNome": "Teste T12", "pacienteCpf": base.CPF, "dataHora": quando}, auth)
    sala, consulta = ag["roomName"], ag["consultaId"]
    _, med = base.api(f"/api/agendamentos/{ag['id']}/token-jitsi", None, auth)
    _, ent = base.api(f"/api/salas/{sala}/validar-cpf", {"cpf": base.CPF})
    if not consentir:   # recusa: o paciente não chega à espera e a consulta não começa
        base.api(f"/api/consultas/{consulta}/consentimento", {"tokenEntrada": ent["tokenEntrada"], "aceito": False, "versaoTermo": base.versao_termo()})
        st, _ = base.api(f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
        base.conferir("quem recusa o termo não chega nem à espera (409)", st == 409)
        return consulta
    base.api(f"/api/consultas/{consulta}/consentimento", {"tokenEntrada": ent["tokenEntrada"], "aceito": True, "versaoTermo": base.versao_termo()})
    base.api(f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})

    pasta_tmp = tempfile.mkdtemp(prefix="medilink-e2e-")   # perfis e logs do Chrome; removida ao final
    perfis = [tempfile.mkdtemp(dir=pasta_tmp), tempfile.mkdtemp(dir=pasta_tmp)]
    logs = [str(Path(pasta_tmp) / "medico.log"), str(Path(pasta_tmp) / "paciente.log")]
    procs = []
    try:
        procs.append(base.abrir_chrome(sala, med["jwt"], True, 47, perfis[0], wav_medico, logs[0]))
        time.sleep(9)
        st, _ = base.api(f"/api/consultas/{consulta}/aceite", None, auth)
        base.conferir("médico aceita o paciente", st == 200)
        time.sleep(2)
        _, pac = base.api(f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
        procs.append(base.abrir_chrome(sala, pac["jwt"], False, 34, perfis[1], wav_paciente, logs[1]))

        # O cliente de verdade (Angular) informa o próprio endpoint ao entrar; aqui o script faz o mesmo pelos logs.
        e_med, e_pac = esperar_endpoint(logs[0]), esperar_endpoint(logs[1])
        base.conferir("os dois navegadores entraram na sala e informaram o endpoint", bool(e_med and e_pac), f"{e_med} / {e_pac}")
        st1, r1 = base.api(f"/api/consultas/{consulta}/participante", {"endpointId": e_med}, auth)
        st2, r2 = base.api(f"/api/consultas/{consulta}/participante", {"endpointId": e_pac, "tokenEntrada": ent["tokenEntrada"]})
        base.conferir("endpoints registrados com o papel certo", (st1, r1.get("papel"), st2, r2.get("papel")) == (200, "MEDICO", 200, "PACIENTE"))

        time.sleep(33)  # o paciente fala até ~43 s depois de o médico entrar
        st, _ = base.api(f"/api/consultas/{consulta}/encerrar", None, auth)
        base.conferir("o médico encerra a consulta", st == 200)
    finally:
        for p in procs:
            try:
                p.wait(timeout=25)
            except Exception:
                p.terminate()
        shutil.rmtree(pasta_tmp, ignore_errors=True)
    return consulta


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
        cons = consumidor_de_resultados()
        consulta = cenario(auth, "A: com consentimento", True, wav_m, wav_p, cons)
        print("aguardando o worker (fila, transcrição)…", flush=True)
        r = esperar_resultado(cons, consulta, 180)
        base.conferir("o resultado chegou ao tópico de resultados", r is not None)
        if r:
            falas = {"MEDICO": [], "PACIENTE": []}
            for s in r["segmentos"]:
                falas.setdefault(s["papel"], []).append(s)
            texto = {p: " ".join(s["texto"] for s in v).lower() for p, v in falas.items()}
            base.conferir("status CONCLUIDO em português", r["status"] == "CONCLUIDO" and r["idioma"] == "pt", f"{r['status']} / {r['idioma']}")
            base.conferir("duas fontes, cada uma com o seu papel", sorted(f["papel"] for f in r["fontes"]) == ["MEDICO", "PACIENTE"],
                          str([f["papel"] for f in r["fontes"]]))
            base.conferir("a fala do MÉDICO foi transcrita e rotulada como médico",
                          "república federativa" in texto["MEDICO"] and "presidencialista" in texto["MEDICO"], texto["MEDICO"][:110])
            base.conferir("a fala do PACIENTE foi transcrita e rotulada como paciente",
                          any(p in texto["PACIENTE"] for p in ("venezuela", "guiana", "suriname", "uruguai")), texto["PACIENTE"][:110])
            base.conferir("os rótulos não estão trocados",
                          "venezuela" not in texto["MEDICO"] and "república federativa" not in texto["PACIENTE"])
            ini = {p: min((s["inicioMs"] for s in v), default=None) for p, v in falas.items()}
            base.conferir("a linha do tempo respeita quem falou primeiro", ini["MEDICO"] is not None and ini["PACIENTE"] is not None
                          and ini["MEDICO"] < ini["PACIENTE"], f"médico {ini['MEDICO']} ms, paciente {ini['PACIENTE']} ms")
            base.conferir("duração coerente com a consulta (> 20 s)", r["duracaoMs"] > 20_000, f"{r['duracaoMs']} ms")

        consulta_b = cenario(auth, "B: recusa do termo", False, wav_m, wav_p, cons)
        print("aguardando 10 s: não deve chegar resultado…", flush=True)
        base.conferir("quem recusa o termo: nada é transcrito (nenhum resultado)", esperar_resultado(cons, consulta_b, 10) is None)
    finally:
        servidor.shutdown()
    sys.exit(1 if base.falhas else 0)


if __name__ == "__main__":
    main()
