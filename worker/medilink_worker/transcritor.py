"""Transcrição de um arquivo de áudio com faster-whisper (modelo turbo, CPU, português, VAD)."""
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from .config import Config

# Heurística do próprio Whisper para descartar trecho sem fala que o modelo "alucina" em silêncio e ruído.
LIMITE_SEM_FALA = 0.6
LIMITE_LOGPROB = -1.0


@dataclass(frozen=True)
class Transcricao:
    segmentos: list[tuple[float, float, str]]   # (inicio_s, fim_s, texto), relativos ao arquivo
    duracao_s: float
    idioma: str
    probabilidade_idioma: float


class Transcritor:
    """Carrega o modelo uma vez e transcreve arquivos. Não registra texto em log."""

    def __init__(self, cfg: Config):
        from faster_whisper import WhisperModel  # import tardio: os testes sem modelo não pagam o custo

        self.cfg = cfg
        self.modelo = WhisperModel(cfg.modelo, device=cfg.dispositivo, compute_type=cfg.precisao,
                                   download_root=str(cfg.pasta_modelos))

    def transcrever(self, audio: BinaryIO | Path, idioma: str | None = None) -> Transcricao:
        segmentos, info = self.modelo.transcribe(
            str(audio) if isinstance(audio, Path) else audio,
            language=idioma or self.cfg.idioma,   # fixo em pt: evita detecção errada em trecho curto
            vad_filter=True,                       # pula trechos sem fala (economiza CPU e evita alucinação)
            vad_parameters={"min_silence_duration_ms": 500},
            beam_size=5,
            condition_on_previous_text=False,      # reduz repetição em cadeia
        )
        uteis = []
        for s in segmentos:  # é um gerador: a decodificação acontece aqui
            if s.no_speech_prob > LIMITE_SEM_FALA and s.avg_logprob < LIMITE_LOGPROB:
                continue
            texto = s.text.strip()
            if texto:
                uteis.append((s.start, s.end, texto))
        return Transcricao(uteis, info.duration, info.language, info.language_probability)
