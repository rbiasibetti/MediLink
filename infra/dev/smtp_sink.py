#!/usr/bin/env python3
"""Servidor SMTP de desenvolvimento: aceita qualquer mensagem e grava em arquivos .eml.

Uso: smtp_sink.py [porta=1025] [pasta=./mails]
Escuta só em 127.0.0.1. Não entrega nada para fora: serve para ver o e-mail do link (T05) sem relay.
"""
import socketserver
import sys
import time
from email import policy
from email.parser import BytesParser
from pathlib import Path

PORTA = int(sys.argv[1]) if len(sys.argv) > 1 else 1025
PASTA = Path(sys.argv[2] if len(sys.argv) > 2 else "mails")


class Handler(socketserver.StreamRequestHandler):
    def responder(self, texto):
        self.wfile.write((texto + "\r\n").encode())

    def handle(self):
        self.responder("220 smtp-sink pronto")
        remetente, destinatarios = "", []
        while True:
            linha = self.rfile.readline()
            if not linha:
                return
            cmd = linha.decode(errors="replace").strip()
            up = cmd.upper()
            if up.startswith(("EHLO", "HELO")):
                self.responder("250 smtp-sink")
            elif up.startswith("MAIL FROM"):
                remetente, destinatarios = cmd[10:], []
                self.responder("250 ok")
            elif up.startswith("RCPT TO"):
                destinatarios.append(cmd[8:])
                self.responder("250 ok")
            elif up == "DATA":
                self.responder("354 termine com <CRLF>.<CRLF>")
                corpo = bytearray()
                while True:
                    l = self.rfile.readline()
                    if l in (b".\r\n", b".\n", b""):
                        break
                    corpo += l[1:] if l.startswith(b"..") else l
                PASTA.mkdir(parents=True, exist_ok=True)
                arq = PASTA / f"{int(time.time() * 1000)}.eml"
                arq.write_bytes(bytes(corpo))
                msg = BytesParser(policy=policy.default).parsebytes(bytes(corpo))
                print(f"[smtp-sink] {arq}  de={remetente} para={destinatarios} assunto={msg['subject']!r}", flush=True)
                self.responder("250 aceito")
            elif up == "QUIT":
                self.responder("221 tchau")
                return
            else:
                self.responder("250 ok")


class Servidor(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Servidor(("127.0.0.1", PORTA), Handler) as s:
        print(f"[smtp-sink] escutando em 127.0.0.1:{PORTA}, gravando em {PASTA}/", flush=True)
        s.serve_forever()
