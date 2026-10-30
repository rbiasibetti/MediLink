#!/usr/bin/env python3
"""T11 ponta a ponta: Jitsi real + backend + audio sink, com Chrome headless e microfone falso.

Cenário A (com consentimento): o paciente consente, o médico aceita, os dois entram e o médico pede a transcrição.
  Esperado: um arquivo Ogg Opus por participante, válido, e NADA gravado antes do aceite.
Cenário B (sem consentimento): idem, mas o paciente não consente. Esperado: nenhum arquivo de áudio.

Pré-requisitos: Jitsi no ar (dev.sh up), backend em dev em :8090 (com MEDILINK_SINK_TOKEN), porta 4200 livre
(pare o ng serve) e Chrome. O Chrome de teste usa perfil temporário e --ignore-certificate-errors só para localhost.

Uso: verify_t11.py <usuario:senha-do-medico>
"""
import base64
import functools
import http.server
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

AQUI = Path(__file__).resolve().parent
BACKEND = os.environ.get("BACKEND", "http://localhost:8090")
PASTA_AUDIO = Path(os.environ.get("MEDILINK_SINK_DIR", tempfile.gettempdir() + "/medilink-audio"))
CHROME = os.environ.get("CHROME_BIN", "google-chrome")
CPF = "529.982.247-25"
falhas = []


def conferir(nome, ok, detalhe=""):
    print(f"[{'OK ' if ok else 'ERRO'}] {nome}" + (f" -> {detalhe}" if detalhe else ""), flush=True)
    if not ok:
        falhas.append(nome)


def api(caminho, corpo=None, auth=None, metodo="POST"):
    h = {"Content-Type": "application/json"}
    if auth:
        h["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    req = urllib.request.Request(BACKEND + caminho, data=None if corpo is None else json.dumps(corpo).encode(), headers=h, method=metodo)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        return e.code, {}


# ---- leitor de Ogg Opus (CRC, estrutura, pacotes e duração) ----
def _tabela_crc():
    t = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if r & 0x80000000 else (r << 1) & 0xFFFFFFFF
        t.append(r)
    return t


CRC = _tabela_crc()


def crc_ogg(dados):
    c = 0
    for b in dados:
        c = ((c << 8) & 0xFFFFFFFF) ^ CRC[((c >> 24) & 0xFF) ^ b]
    return c


def ler_ogg(caminho):
    """Retorna dict com paginas, pacotes_audio, crc_ok, bos/eos e duração em segundos (granule final - pré-atraso)."""
    d = Path(caminho).read_bytes()
    pos, paginas, pacotes, crc_ok, seqs = 0, [], [], True, []
    atual = b""
    while pos < len(d):
        assert d[pos:pos + 4] == b"OggS", f"assinatura inválida em {pos}"
        tipo, granule, serial, seq, crc, nseg = struct.unpack_from("<BqIIIB", d, pos + 5)
        tabela = d[pos + 27:pos + 27 + nseg]
        corpo_len = sum(tabela)
        pagina = bytearray(d[pos:pos + 27 + nseg + corpo_len])
        pagina[22:26] = b"\x00\x00\x00\x00"
        crc_ok &= crc_ogg(bytes(pagina)) == crc
        off = pos + 27 + nseg
        for s in tabela:
            atual += d[off:off + s]
            off += s
            if s < 255:
                pacotes.append(atual)
                atual = b""
        paginas.append((tipo, granule, seq))
        seqs.append(seq)
        pos += 27 + nseg + corpo_len
    return {
        "paginas": len(paginas), "crc_ok": crc_ok, "seq_ok": seqs == list(range(len(seqs))),
        "head": pacotes[0][:8] == b"OpusHead", "tags": pacotes[1][:8] == b"OpusTags",
        "audio": len(pacotes) - 2, "eos": bool(paginas[-1][0] & 4),
        "duracao": (paginas[-1][1] - 312) / 48000.0,
    }


# ---- navegador de teste ----
def abrir_chrome(room, jwt, pedir, ate, perfil, wav=None, log=None):
    """Chrome headless de teste. Com `wav`, o microfone falso toca esse arquivo (uma vez) em vez do bipe;
    com `log`, o console do navegador (inclusive as linhas "HARNESS ...") vai para esse arquivo."""
    url = f"http://localhost:4200/harness.html#room={room}&jwt={jwt}&pedir={1 if pedir else 0}&ate={ate}"
    args = [CHROME, "--headless=new", "--no-sandbox", "--disable-gpu", f"--user-data-dir={perfil}",
            "--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream", "--ignore-certificate-errors",
            "--autoplay-policy=no-user-gesture-required", "--enable-logging=stderr", "--v=0"]
    if wav:
        args.append(f"--use-file-for-fake-audio-capture={wav}%noloop")
    return subprocess.Popen(args + [url], stdout=subprocess.DEVNULL, stderr=open(log, "w") if log else subprocess.DEVNULL)


def cenario(auth, nome, consentir):
    print(f"\n=== {nome} ===", flush=True)
    quando = (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    _, ag = api("/api/agendamentos", {"pacienteNome": "Teste T11", "pacienteCpf": CPF, "dataHora": quando}, auth)
    sala, consulta = ag["roomName"], ag["consultaId"]
    _, med = api(f"/api/agendamentos/{ag['id']}/token-jitsi", None, auth)
    _, ent = api(f"/api/salas/{sala}/validar-cpf", {"cpf": CPF})
    st, _ = api(f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("paciente chega e fica em espera", st == 202)
    if consentir:
        st, _ = api(f"/api/consultas/{consulta}/consentimento", {"tokenEntrada": ent["tokenEntrada"], "aceito": True, "versaoTermo": "v1"})
        conferir("paciente consente", st == 200)

    pasta_tmp = tempfile.mkdtemp(prefix="medilink-e2e-")   # perfis do Chrome; removida ao final
    perfis, procs = [tempfile.mkdtemp(dir=pasta_tmp), tempfile.mkdtemp(dir=pasta_tmp)], []
    try:
        procs.append(abrir_chrome(sala, med["jwt"], True, 34, perfis[0]))      # médico entra e pede a transcrição
        time.sleep(9)
        t_aceite = time.time()
        st, det = api(f"/api/consultas/{consulta}/aceite", None, auth)
        conferir("médico aceita o paciente", st == 200)
        time.sleep(2)
        st, pac = api(f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
        conferir("paciente recebe o JWT", st == 200 and pac.get("jwt"))
        procs.append(abrir_chrome(sala, pac["jwt"], False, 22, perfis[1]))     # paciente entra
        _, d = api(f"/api/consultas/{consulta}", None, auth, "GET")
        conferir("o backend informa se a captura está autorizada", d.get("capturaAutorizada") is consentir, str(d.get("capturaAutorizada")))
        time.sleep(32)
    finally:
        for p in procs:
            try:
                p.wait(timeout=20)
            except subprocess.TimeoutExpired:
                p.terminate()
        shutil.rmtree(pasta_tmp, ignore_errors=True)
    time.sleep(5)  # o JVB fecha o exportador e o sink fecha os arquivos
    pasta = PASTA_AUDIO / consulta
    arquivos = sorted(pasta.glob("*.opus")) if pasta.exists() else []
    _, fim = api(f"/api/consultas/{consulta}", None, auth, "GET")
    inicio_ms = datetime.fromisoformat(fim["inicioEm"].replace("Z", "+00:00")).timestamp() * 1000
    return arquivos, inicio_ms, consulta


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    auth = sys.argv[1]
    (AQUI / "harness.html").exists() or sys.exit("harness.html ausente")

    class Silencioso(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a, **k):
            pass

    servidor = http.server.ThreadingHTTPServer(("127.0.0.1", 4200), functools.partial(Silencioso, directory=str(AQUI)))
    threading.Thread(target=servidor.serve_forever, daemon=True).start()
    try:
        arquivos, inicio_aceite_ms, consulta = cenario(auth, "A: com consentimento", True)
        conferir("há um arquivo de áudio por participante (médico e paciente)", len(arquivos) == 2, f"{len(arquivos)} arquivo(s)")
        for arq in arquivos:
            info = ler_ogg(arq)
            conferir(f"{arq.name}: Ogg Opus válido (CRC, páginas em ordem, OpusHead/OpusTags, EOS)",
                     info["crc_ok"] and info["seq_ok"] and info["head"] and info["tags"] and info["eos"], str(info))
            conferir(f"{arq.name}: tem áudio ({info['audio']} pacotes, {info['duracao']:.1f} s)", info["audio"] > 200 and info["duracao"] > 8)
            # O nome traz o instante em que o arquivo foi aberto = primeiro quadro autorizado.
            aberto_ms = int(arq.stem.rsplit("-", 1)[1])
            conferir(f"{arq.name}: nada antes do aceite (aberto {aberto_ms - inicio_aceite_ms:+.0f} ms depois do aceite)",
                     aberto_ms >= inicio_aceite_ms - 50)
            gravado = arq.stat().st_mtime - aberto_ms / 1000.0
            conferir(f"{arq.name}: linha do tempo coerente (áudio {info['duracao']:.1f} s x gravação {gravado:.1f} s)",
                     abs(info["duracao"] - gravado) < 2.5)
            conferir(f"{arq.name}: permissão só do dono", (arq.stat().st_mode & 0o777) == 0o600)

        arquivos_b, _, _ = cenario(auth, "B: sem consentimento", False)
        conferir("sem consentimento NENHUM áudio é gravado", len(arquivos_b) == 0, f"{len(arquivos_b)} arquivo(s)")
    finally:
        servidor.shutdown()
    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
