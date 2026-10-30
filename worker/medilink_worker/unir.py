"""Junta as falas das fontes (uma por participante) numa única linha do tempo rotulada por locutor."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Fala:
    papel: str
    inicio_ms: int   # relativo ao início da consulta (o primeiro quadro de áudio de qualquer fonte)
    fim_ms: int
    texto: str


def unir(por_fonte: list[tuple[str, int, list[tuple[float, float, str]]]]) -> tuple[list[Fala], int]:
    """por_fonte: (papel, iniciado_em_ms, [(inicio_s, fim_s, texto)]) com os tempos relativos ao arquivo.

    Cada arquivo começa no instante em que o sink o abriu, então o tempo absoluto de uma fala é o início do
    arquivo mais o tempo dela dentro do arquivo. A base (zero) é o arquivo que começou primeiro. Falas
    sobrepostas das duas pessoas são mantidas (interrupção é fala de verdade) e ordenadas pelo início.
    """
    if not por_fonte:
        return [], 0
    base = min(inicio for _, inicio, _ in por_fonte)
    falas = []
    for papel, inicio_arquivo, segmentos in por_fonte:
        deslocamento = inicio_arquivo - base
        for ini_s, fim_s, texto in segmentos:
            texto = texto.strip()
            if texto:
                falas.append(Fala(papel, deslocamento + round(ini_s * 1000), deslocamento + round(fim_s * 1000), texto))
    falas.sort(key=lambda f: (f.inicio_ms, f.fim_ms, f.papel))
    return falas, max((f.fim_ms for f in falas), default=0)
