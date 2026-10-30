"""Cifra um áudio no formato MLA1, só para os testes (o worker nunca cifra: ele só lê o que o backend gravou)."""
import os
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

BLOCO = 16 * 1024


def cifrar(claro: bytes, mestra: bytes, id_: str = "k1", sal: bytes | None = None) -> bytes:
    sal = sal or os.urandom(16)
    ident = id_.encode()
    cabecalho = b"MLA1" + bytes([len(ident)]) + ident + sal + struct.pack(">I", BLOCO)
    aead = AESGCM(HKDF(algorithm=hashes.SHA256(), length=32, salt=sal, info=b"audio-v1").derive(mestra))
    saida = bytearray(cabecalho)
    for i, ini in enumerate(range(0, len(claro), BLOCO)):
        c = aead.encrypt(b"\0\0\0\0" + struct.pack(">Q", i), claro[ini:ini + BLOCO], cabecalho)
        saida += struct.pack(">I", len(c)) + c
    return bytes(saida)
