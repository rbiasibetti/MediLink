import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


def job_json(fontes, job_id="job-1", consulta="c-1", versao=1):
    return json.dumps({"versao": versao, "jobId": job_id, "consultaId": consulta, "idioma": "pt", "encerradaEm": None, "fontes": fontes})


def fonte_json(arquivo="c-1/a0-1.opus", papel="MEDICO", iniciado="2026-10-29T12:00:00Z", audio="a1", endpoint="e1"):
    return {"audioId": audio, "arquivo": arquivo, "endpointId": endpoint, "papel": papel, "iniciadoEm": iniciado}
