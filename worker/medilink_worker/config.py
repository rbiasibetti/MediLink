"""Configuração por variáveis de ambiente (os mesmos nomes do backend quando o assunto é o mesmo)."""
import os
from dataclasses import dataclass
from pathlib import Path

from .cifra import Chaves

RAIZ = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Config:
    kafka: str = os.environ.get("MEDILINK_KAFKA_BOOTSTRAP", "localhost:9092")
    # TLS até o broker (T14): MEDILINK_KAFKA_SECURITY_PROTOCOL=SSL (ou SASL_SSL) e MEDILINK_KAFKA_CA com o certificado da CA.
    kafka_protocolo: str = os.environ.get("MEDILINK_KAFKA_SECURITY_PROTOCOL", "PLAINTEXT")
    kafka_ca: str = os.environ.get("MEDILINK_KAFKA_CA", "")
    topico_jobs: str = os.environ.get("MEDILINK_TOPICO_JOBS", "medilink.transcricao.jobs")
    topico_resultados: str = os.environ.get("MEDILINK_TOPICO_RESULTADOS", "medilink.transcricao.resultados")
    grupo: str = os.environ.get("MEDILINK_WORKER_GRUPO", "medilink-transcricao")
    # Pasta dos arquivos de áudio transitórios do sink (a mesma MEDILINK_SINK_DIR do backend).
    pasta_audio: Path = Path(os.environ.get("MEDILINK_SINK_DIR", "/tmp/medilink-audio"))
    # Cifra em repouso (T14): as mesmas chaves do backend. Sem chave, nenhum áudio cifrado pode ser lido.
    chaves: Chaves = Chaves.de_ambiente(os.environ)
    # Recusa áudio sem cifra (só desligar para ler arquivos de antes da T14).
    exigir_audio_cifrado: bool = os.environ.get("MEDILINK_AUDIO_CIFRADO_OBRIGATORIO", "1") != "0"
    modelo: str = os.environ.get("MEDILINK_WHISPER_MODELO", "turbo")
    pasta_modelos: Path = Path(os.environ.get("MEDILINK_WHISPER_DIR", str(RAIZ / "models")))
    dispositivo: str = os.environ.get("MEDILINK_WHISPER_DEVICE", "cpu")
    # int8 em CPU: equilibra qualidade e velocidade sem GPU.
    precisao: str = os.environ.get("MEDILINK_WHISPER_COMPUTE", "int8")
    idioma: str = os.environ.get("MEDILINK_WHISPER_IDIOMA", "pt")

    def kafka_base(self) -> dict:
        """Parâmetros de conexão comuns ao consumidor e ao produtor. Fora do PLAINTEXT, exige a CA para verificar o broker."""
        base = {"bootstrap.servers": self.kafka}
        if self.kafka_protocolo != "PLAINTEXT":
            if not self.kafka_ca:
                raise ValueError("MEDILINK_KAFKA_CA é obrigatório com TLS para o broker")
            base.update({"security.protocol": self.kafka_protocolo, "ssl.ca.location": self.kafka_ca,
                         "enable.ssl.certificate.verification": True})
        return base
