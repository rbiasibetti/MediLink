#!/usr/bin/env python3
"""Receptor de webhook para desenvolvimento (T19): confere a assinatura e mostra o que chegou, sem imprimir o texto da consulta.

Uso: webhook_sink.py <porta> <segredo-whsec>   (por exemplo: webhook_sink.py 9099 whsec_...)
Responde 200 se a assinatura e a idade da mensagem estão corretas; senão 401. Guarda os eventos recebidos em memória
(importável por outros scripts: `from webhook_sink import iniciar`).
"""
import hashlib
import hmac
import http.server
import json
import sys
import threading
import time

TOLERANCIA_S = 300


def assinatura_valida(segredo: str, cabecalho: str, corpo: bytes, agora: float | None = None) -> bool:
    try:
        t, v1 = cabecalho.split(",")
        t = int(t.removeprefix("t="))
        v1 = v1.removeprefix("v1=")
    except (ValueError, AttributeError):
        return False
    esperado = hmac.new(segredo.encode(), f"{t}.".encode() + corpo, hashlib.sha256).hexdigest()
    return abs((agora or time.time()) - t) <= TOLERANCIA_S and hmac.compare_digest(esperado, v1)


def iniciar(porta: int, segredo: str):
    """Sobe o receptor numa thread. Devolve (servidor, lista de eventos recebidos)."""
    recebidos = []

    class H(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            corpo = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            ok = assinatura_valida(segredo, self.headers.get("X-MediLink-Signature", ""), corpo)
            if ok:
                recebidos.append({"caminho": self.path, "evento": self.headers.get("X-MediLink-Event"),
                                  "eventoId": self.headers.get("X-MediLink-Event-Id"), "corpo": json.loads(corpo)})
            self.send_response(200 if ok else 401)
            self.end_headers()

        def log_message(self, *a, **k):
            pass

    servidor = http.server.ThreadingHTTPServer(("127.0.0.1", porta), H)
    threading.Thread(target=servidor.serve_forever, daemon=True).start()
    return servidor, recebidos


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    _, eventos = iniciar(int(sys.argv[1]), sys.argv[2])
    visto = 0
    print(f"receptor em http://127.0.0.1:{sys.argv[1]} (Ctrl+C para sair)", flush=True)
    while True:
        time.sleep(1)
        while visto < len(eventos):
            e = eventos[visto]
            visto += 1
            print(f"{e['evento']} id={e['eventoId']} caminho={e['caminho']} (assinatura OK; texto não exibido)", flush=True)
