"""Processa um job: transcreve cada fonte existente, junta as falas e monta o resultado."""
import logging
from pathlib import Path

from .config import Config
from .mensagens import Job, JobInvalido, caminho_seguro, resultado_json
from .unir import unir

log = logging.getLogger("medilink.worker")


def processar(job: Job, transcritor, cfg: Config) -> str:
    """Devolve o JSON do resultado. Falha por fonte não derruba as demais; sem nenhuma fonte legível, o status é FALHA."""
    por_fonte, resumo, ignoradas = [], [], 0
    for f in job.fontes:
        try:
            arquivo = caminho_seguro(cfg.pasta_audio, f.arquivo)
            if not arquivo.is_file():
                raise FileNotFoundError("arquivo de áudio ausente")
            t = transcritor.transcrever(arquivo, job.idioma)
        except (JobInvalido, OSError, RuntimeError, ValueError) as e:
            ignoradas += 1
            log.warning("job %s: fonte %s ignorada (%s)", job.job_id, f.audio_id, type(e).__name__)  # nunca a mensagem
            continue
        por_fonte.append((f.papel, f.iniciado_em_ms, t.segmentos))
        resumo.append({"audioId": f.audio_id, "papel": f.papel, "duracaoMs": round(t.duracao_s * 1000), "falas": len(t.segmentos)})

    if not por_fonte:
        return resultado_json(job, "FALHA", [], [], 0, erro="NenhumaFonteLegivel")
    falas, duracao_ms = unir(por_fonte)
    segmentos = [{"papel": x.papel, "inicioMs": x.inicio_ms, "fimMs": x.fim_ms, "texto": x.texto} for x in falas]
    log.info("job %s: %d fonte(s), %d ignorada(s), %d fala(s)", job.job_id, len(por_fonte), ignoradas, len(segmentos))
    return resultado_json(job, "CONCLUIDO", segmentos, resumo, duracao_ms)
