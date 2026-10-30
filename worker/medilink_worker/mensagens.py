"""Contrato das mensagens da fila (docs/plan.md, "Fila de transcrição"). Só referências no job; o resultado
carrega o texto, que é o produto e dado sensível de saúde."""
import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path, PurePosixPath

PAPEIS = {"MEDICO", "PACIENTE", "DESCONHECIDO"}


class JobInvalido(ValueError):
    """Mensagem que não segue o contrato. Nunca inclui o conteúdo da mensagem na descrição."""


@dataclass(frozen=True)
class Fonte:
    audio_id: str
    arquivo: str          # relativo à pasta de áudio do sink
    endpoint_id: str | None
    papel: str
    iniciado_em_ms: int   # epoch em ms do primeiro quadro gravado


@dataclass(frozen=True)
class Job:
    job_id: str
    consulta_id: str
    idioma: str
    fontes: list[Fonte] = field(default_factory=list)


def _epoch_ms(iso: str) -> int:
    return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)


def ler_job(bruto: bytes | str) -> Job:
    try:
        d = json.loads(bruto)
        if d.get("versao") != 1:
            raise JobInvalido("versão do contrato não suportada")
        fontes = []
        for f in d["fontes"]:
            papel = f.get("papel", "DESCONHECIDO")
            fontes.append(Fonte(
                audio_id=str(f["audioId"]), arquivo=str(f["arquivo"]), endpoint_id=f.get("endpointId"),
                papel=papel if papel in PAPEIS else "DESCONHECIDO", iniciado_em_ms=_epoch_ms(f["iniciadoEm"])))
        return Job(job_id=str(d["jobId"]), consulta_id=str(d["consultaId"]), idioma=d.get("idioma") or "pt", fontes=fontes)
    except JobInvalido:
        raise
    except (ValueError, KeyError, TypeError, AttributeError) as e:
        raise JobInvalido(f"job fora do contrato ({type(e).__name__})") from None


def caminho_seguro(pasta: Path, relativo: str) -> Path:
    """Resolve o arquivo dentro da pasta do sink; recusa caminho absoluto, '..' e links que saiam dela."""
    p = PurePosixPath(relativo)
    if p.is_absolute() or ".." in p.parts or not p.parts:
        raise JobInvalido("caminho de áudio inválido")
    completo = (pasta / Path(*p.parts)).resolve()
    if pasta.resolve() not in completo.parents:
        raise JobInvalido("caminho de áudio fora da pasta do sink")
    return completo


def resultado_json(job: Job, status: str, segmentos: list[dict], fontes: list[dict], duracao_ms: int, erro: str | None = None) -> str:
    r = {"versao": 1, "jobId": job.job_id, "consultaId": job.consulta_id, "status": status, "idioma": job.idioma,
         "duracaoMs": duracao_ms, "segmentos": segmentos, "fontes": fontes}
    if erro:
        r["erro"] = erro  # só o tipo do erro, nunca mensagem (poderia conter texto da consulta)
    return json.dumps(r, ensure_ascii=False, separators=(",", ":"))
