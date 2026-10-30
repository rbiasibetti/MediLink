#!/usr/bin/env python3
"""Verificação do T08 contra o backend e o Prosody reais: o paciente só entra depois do aceite do médico.

Cenário: o paciente chega ANTES do médico, espera, tenta entrar sem credencial e só entra depois do aceite.

Uso: verify_t08.py <usuario:senha-do-medico> [backend=http://localhost:8090]
"""
import base64
import json
import sys
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_auth  # noqa: E402
from verify_t07 import api, consentir, papel  # noqa: E402

CPF = "529.982.247-25"


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    auth = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8090"
    falhas = []

    def conferir(nome, ok, detalhe=""):
        print(f"[{'OK ' if ok else 'ERRO'}] {nome}" + (f" -> {detalhe}" if detalhe else ""))
        if not ok:
            falhas.append(nome)

    quando = (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    _, ag = api(base, "/api/agendamentos", {"pacienteNome": "Maria Souza", "pacienteCpf": CPF, "dataHora": quando}, auth)
    sala, consulta = ag["roomName"], ag["consultaId"]

    # O médico ainda não está na sala. O paciente passa pelo CPF e fica em espera.
    _, ent = api(base, f"/api/salas/{sala}/validar-cpf", {"cpf": CPF})
    consentir(base, consulta, ent["tokenEntrada"])  # sem o termo aceito o paciente nem chega à espera
    st, esp = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("paciente que chega antes do médico fica em espera (202, sem JWT)", st == 202 and esp.get("jwt") is None, esp.get("estado", ""))

    # Sem credencial do Jitsi, a sala (ainda inexistente, aberta por padrão) não é alcançável.
    r, _ = verify_auth.entrar(sala, None, "paciente")
    conferir("Prosody recusa o paciente sem JWT", r != "ACEITO", r)
    r, _ = verify_auth.entrar(sala, ent["tokenEntrada"], "paciente")
    conferir("token de entrada não serve como JWT do Jitsi", r != "ACEITO", r)

    # O médico entra, cria a sala, e vê que há paciente aguardando.
    _, med = api(base, f"/api/agendamentos/{ag['id']}/token-jitsi", None, auth)
    r, x = verify_auth.entrar(sala, med["jwt"], "medico")
    conferir("médico entra na sala como moderador", r == "ACEITO" and papel(x, "medico") == ("owner", "moderator"), str(papel(x, "medico")))

    req = urllib.request.Request(f"{base}/api/consultas/{consulta}", headers={"Authorization": "Basic " + base64.b64encode(auth.encode()).decode()})
    det = json.load(urllib.request.urlopen(req, timeout=20))
    conferir("médico vê o paciente aguardando", det["pacienteAguardando"] is True, det["status"])

    # Mesmo com o médico na sala, o paciente continua sem credencial até o aceite.
    st, esp = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("médico na sala não basta: paciente segue sem JWT", st == 202 and esp.get("jwt") is None)

    st, acc = api(base, f"/api/consultas/{consulta}/aceite", None, auth)
    conferir("médico aceita o paciente", st == 200 and acc["status"] == "EM_ANDAMENTO", acc.get("status", ""))

    st, pac = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("após o aceite o paciente recebe o JWT", st == 200 and pac.get("jwt"), pac.get("estado", ""))
    r, x = verify_auth.entrar(sala, pac["jwt"], "paciente")
    p = papel(x, "paciente")
    conferir("paciente entra na sala sem moderação", r == "ACEITO" and p and p[1] != "moderator", f"{r} {p}")

    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
