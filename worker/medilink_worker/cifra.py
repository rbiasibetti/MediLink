"""Decifra os arquivos de áudio transitórios (T14). Formato MLA1, o mesmo de backend/.../crypto/Cifrador.java.

Cabeçalho: "MLA1" | tamanho do id (1) | id da chave | sal (16) | tamanho do bloco (4, big-endian). Depois, blocos
{tamanho (4) | cifrado+tag}. Chave do arquivo = HKDF-SHA256(chave mestra, sal, info="audio-v1"); nonce = 4 zeros +
índice do bloco (8, big-endian); o cabeçalho inteiro é dado autenticado. O conteúdo claro só existe em memória.
"""
import base64
import struct
from dataclasses import dataclass
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAGIA = b"MLA1"
TAG = 16
BLOCO_MAXIMO = 1 << 24


class ErroDeCifra(ValueError):
    """Arquivo adulterado, truncado, de formato desconhecido ou cifrado com uma chave que não temos."""


def e_cifrado(inicio: bytes) -> bool:
    return inicio[:4] == MAGIA


@dataclass(frozen=True)
class Chaves:
    """Chaves mestras por id: a ativa e as aposentadas que ainda decifram arquivos antigos."""

    por_id: dict[str, bytes]

    @staticmethod
    def de_ambiente(env) -> "Chaves":
        """MEDILINK_DATA_KEY (+ _ID, padrão k1) e MEDILINK_DATA_KEYS_ANTERIORES="id:base64,id:base64". Nada é registrado em log."""
        chaves: dict[str, bytes] = {}
        atual = env.get("MEDILINK_DATA_KEY", "")
        if atual:
            chaves[env.get("MEDILINK_DATA_KEY_ID", "k1")] = _mestra(atual)
        for par in filter(None, env.get("MEDILINK_DATA_KEYS_ANTERIORES", "").split(",")):
            id_, _, valor = par.partition(":")
            chaves.setdefault(id_.strip(), _mestra(valor))
        return Chaves(chaves)


def _mestra(base64_: str) -> bytes:
    try:
        k = base64.b64decode(base64_.strip(), validate=True)
    except ValueError as e:
        raise ErroDeCifra("chave mestra não é Base64 válido") from e
    if len(k) != 32:
        raise ErroDeCifra("chave mestra precisa ter 32 bytes")
    return k


def decifrar_audio(dados: bytes, chaves: Chaves) -> bytes:
    if not e_cifrado(dados) or len(dados) < 5:
        raise ErroDeCifra("arquivo de áudio sem o cabeçalho de cifra")
    id_len = dados[4]
    fim_cab = 5 + id_len + 16 + 4
    if len(dados) < fim_cab:
        raise ErroDeCifra("cabeçalho de áudio truncado")
    cabecalho = dados[:fim_cab]
    id_ = dados[5:5 + id_len].decode("utf-8", errors="replace")
    sal = dados[5 + id_len:5 + id_len + 16]
    (bloco,) = struct.unpack(">I", dados[fim_cab - 4:fim_cab])
    mestra = chaves.por_id.get(id_)
    if mestra is None or not 0 < bloco <= BLOCO_MAXIMO:
        raise ErroDeCifra("chave desconhecida ou cabeçalho inválido")
    chave = HKDF(algorithm=hashes.SHA256(), length=32, salt=sal, info=b"audio-v1").derive(mestra)
    aead = AESGCM(chave)
    claro = bytearray()
    pos, indice = fim_cab, 0
    while pos < len(dados):
        if pos + 4 > len(dados):
            raise ErroDeCifra("arquivo de áudio truncado")
        (compr,) = struct.unpack(">I", dados[pos:pos + 4])
        pos += 4
        if compr < TAG or compr > bloco + TAG or pos + compr > len(dados):
            raise ErroDeCifra("bloco de áudio com tamanho inválido ou truncado")
        try:
            claro += aead.decrypt(b"\0\0\0\0" + struct.pack(">Q", indice), dados[pos:pos + compr], cabecalho)
        except InvalidTag as e:
            raise ErroDeCifra("bloco de áudio adulterado ou chave errada") from e
        pos += compr
        indice += 1
    return bytes(claro)


def ler_audio(caminho: Path, chaves: Chaves, exigir_cifra: bool = True) -> bytes:
    """Lê o arquivo e devolve o áudio claro (em memória). Sem a cifra, só passa se exigir_cifra for falso (dev antigo)."""
    dados = caminho.read_bytes()
    if e_cifrado(dados):
        return decifrar_audio(dados, chaves)
    if exigir_cifra:
        raise ErroDeCifra("arquivo de áudio não está cifrado")
    return dados
