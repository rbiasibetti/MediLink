#!/usr/bin/env python3
"""Verificação do T07 contra o backend e o Prosody reais.

Percorre o fluxo completo pela API (médico cria o agendamento; paciente confirma o CPF e troca o token) e
confere no Jitsi: o médico entra como moderador (owner) e o paciente como participante comum.

Uso: verify_t07.py <usuario:senha-do-medico> [backend=http://localhost:8090]
"""
import base64
import json
import re
import ssl
import sys
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_auth  # noqa: E402

CPF = "529.982.247-25"
CPF_ERRADO = "111.444.777-35"


def api(base, caminho, corpo=None, auth=None):
    h = {"Content-Type": "application/json"}
    if auth:
        h["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    data = None if corpo is None else json.dumps(corpo).encode()
    req = urllib.request.Request(base + caminho, data=data, headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            txt = r.read().decode()
            return r.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        return e.code, {}


def papel(xml, nick):
    """(afiliação, papel) do ocupante `nick` na presença da sala, ou None."""
    for m in re.finditer(r"<presence[^>]*from='[^']*/" + re.escape(nick) + r"'.*?</presence>", xml, re.S):
        item = re.search(r"<item([^>]*)/?>", m.group(0))
        if item:
            attrs = dict(re.findall(r"(\w+)='([^']*)'", item.group(1)))
            return attrs.get("affiliation"), attrs.get("role")
    return None


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
    st, ag = api(base, "/api/agendamentos", {"pacienteNome": "Maria Souza", "pacienteCpf": CPF, "dataHora": quando}, auth)
    conferir("médico cria o agendamento", st == 201)
    sala = ag["roomName"]

    st, med = api(base, f"/api/agendamentos/{ag['id']}/token-jitsi", None, auth)
    conferir("médico recebe o JWT do Jitsi", st == 200 and "jwt" in med)

    st, _ = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": "forjado"})
    conferir("paciente sem passar pelo CPF não recebe JWT", st == 403)
    st, _ = api(base, f"/api/salas/{sala}/validar-cpf", {"cpf": CPF_ERRADO})
    conferir("CPF errado não gera token de entrada", st == 403)

    st, ent = api(base, f"/api/salas/{sala}/validar-cpf", {"cpf": CPF})
    conferir("CPF correto gera token de entrada", st == 200)
    # T08: o paciente espera e o médico o aceita antes de a troca devolver o JWT.
    st, _ = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("paciente fica em espera até o aceite (202)", st == 202)
    st, _ = api(base, f"/api/consultas/{ag['consultaId']}/aceite", None, auth)
    conferir("médico aceita o paciente", st == 200)
    st, pac = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("paciente troca o token de entrada pelo JWT do Jitsi", st == 200 and "jwt" in pac)

    # No Jitsi real: o médico entra primeiro (cria a sala via Jicofo); depois o paciente.
    r_med, x_med = verify_auth.entrar(sala, med["jwt"], "medico")
    conferir("Prosody aceita o médico na sala", r_med == "ACEITO", r_med)
    p_med = papel(x_med, "medico")
    conferir("médico entra como owner/moderator", p_med == ("owner", "moderator"), str(p_med))

    r_pac, x_pac = verify_auth.entrar(sala, pac["jwt"], "paciente")
    conferir("Prosody aceita o paciente na sala", r_pac == "ACEITO", r_pac)
    p_pac = papel(x_pac, "paciente")
    conferir("paciente entra sem moderação", p_pac is not None and p_pac[1] != "moderator" and p_pac[0] != "owner", str(p_pac))

    # O JWT do paciente não vale em outra sala; o do médico também é por sala.
    r_outra, _ = verify_auth.entrar(sala + "-outra", pac["jwt"], "x")
    conferir("JWT do paciente não abre outra sala", r_outra != "ACEITO", r_outra)

    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
