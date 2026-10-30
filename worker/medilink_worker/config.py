"""Configuração por variáveis de ambiente (os mesmos nomes do backend quando o assunto é o mesmo)."""
import os
from dataclasses import dataclass
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Config:
    kafka: str = os.environ.get("MEDILINK_KAFKA_BOOTSTRAP", "localhost:9092")
    topico_jobs: str = os.environ.get("MEDILINK_TOPICO_JOBS", "medilink.transcricao.jobs")
    topico_resultados: str = os.environ.get("MEDILINK_TOPICO_RESULTADOS", "medilink.transcricao.resultados")
    grupo: str = os.environ.get("MEDILINK_WORKER_GRUPO", "medilink-transcricao")
    # Pasta dos arquivos de áudio transitórios do sink (a mesma MEDILINK_SINK_DIR do backend).
    pasta_audio: Path = Path(os.environ.get("MEDILINK_SINK_DIR", "/tmp/medilink-audio"))
    modelo: str = os.environ.get("MEDILINK_WHISPER_MODELO", "turbo")
    pasta_modelos: Path = Path(os.environ.get("MEDILINK_WHISPER_DIR", str(RAIZ / "models")))
    dispositivo: str = os.environ.get("MEDILINK_WHISPER_DEVICE", "cpu")
    # int8 em CPU: equilibra qualidade e velocidade sem GPU.
    precisao: str = os.environ.get("MEDILINK_WHISPER_COMPUTE", "int8")
    idioma: str = os.environ.get("MEDILINK_WHISPER_IDIOMA", "pt")
