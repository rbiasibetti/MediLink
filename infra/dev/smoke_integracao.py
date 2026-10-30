#!/usr/bin/env python3
"""Fumaça das etapas T16 a T19 contra o backend em dev (HTTP de verdade, sem navegador).

Cobre: administrador (senha + TOTP) → sistema integrado e API key → agendamento por API (CPF, e-mail, webhook) →
médico entra por link de uso único → paciente aceita o termo e espera → médico aceita e encerra pela sessão do link →
a consulta termina sem áudio (não há Jitsi aqui) → o webhook "transcricao.indisponivel" chega ASSINADO, no destino
cadastrado + caminho/parâmetros do agendamento → o painel mostra o incidente → a key revogada deixa de funcionar.

Pré-requisito: backend em dev em :8090 iniciado com MEDILINK_CONSULTA_TOLERANCIA_REABERTURA=0s (senão o job só é
publicado depois da janela de 10 min). O segredo do TOTP do administrador de dev fica em ~/.cache/medilink/smoke-totp
(fora do repositório).

Uso: smoke_integracao.py [senha-do-admin-de-dev]
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import webhook_sink  # noqa: E402

BACKEND = os.environ.get("BACKEND", "http://localhost:8090")
EMAIL = os.environ.get("MEDILINK_ADMIN_EMAIL", "admin@medilink.local")
SENHA = sys.argv[1] if len(sys.argv) > 1 else "dev-only-admin-change-me-1"
ARQ_TOTP = Path.home() / ".cache" / "medilink" / "smoke-totp"
CPF = "529.982.247-25"
falhas = []


def conferir(nome, ok, detalhe=""):
    print(f"[{'OK ' if ok else 'ERRO'}] {nome}" + (f" -> {detalhe}" if detalhe else ""), flush=True)
    if not ok:
        falhas.append(nome)


def chamar(metodo, caminho, corpo=None, cab=None):
    h = {"Content-Type": "application/json", **(cab or {})}
    req = urllib.request.Request(BACKEND + caminho, data=None if corpo is None else json.dumps(corpo).encode(), headers=h, method=metodo)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t) if t else {}
        except ValueError:
            return e.code, {}


def totp(segredo, agora=None):
    chave = base64.b32decode(segredo + "=" * (-len(segredo) % 8))
    passo = int((agora or time.time()) // 30)
    h = hmac.new(chave, struct.pack(">Q", passo), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return f"{(struct.unpack('>I', h[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000:06d}"


def entrar_admin():
    ARQ_TOTP.parent.mkdir(parents=True, exist_ok=True)
    if ARQ_TOTP.exists():
        segredo = ARQ_TOTP.read_text().strip()
    else:
        st, r = chamar("POST", "/api/admin/login", {"email": EMAIL, "senha": SENHA})
        if st != 200 or r.get("etapa") != "ENROLAR_TOTP":
            sys.exit(f"login do administrador de dev falhou ({st}); o TOTP já foi cadastrado sem o arquivo {ARQ_TOTP}?")
        segredo = r["segredoTotp"]
        ARQ_TOTP.write_text(segredo)
        ARQ_TOTP.chmod(0o600)
    for tentativa in range(2):  # o mesmo passo não vale duas vezes: espera o seguinte se preciso
        st, r = chamar("POST", "/api/admin/login", {"email": EMAIL, "senha": SENHA, "codigo": totp(segredo)})
        if st == 200 and r.get("etapa") == "OK":
            return {"Authorization": "Bearer " + r["token"]}
        time.sleep(31)
    sys.exit("não foi possível entrar como administrador")


def main():
    admin = entrar_admin()
    conferir("administrador entra com senha + TOTP", True)
    st, _ = chamar("GET", "/api/admin/painel")
    conferir("sem sessão a administração responde 401", st == 401)

    # ---- T18: sistema integrado, key e webhook
    nome = "Smoke " + uuid.uuid4().hex[:8]
    _, i = chamar("POST", "/api/admin/integradores", {"nome": nome}, admin)
    _, k = chamar("POST", f"/api/admin/integradores/{i['id']}/chaves", {"nome": "smoke", "escopos": ["agendamento:escrever", "agendamento:ler", "sala:iniciar"]}, admin)
    key = {"X-API-Key": k["chave"]}
    porta = 9099
    _, w = chamar("PUT", f"/api/admin/integradores/{i['id']}/webhook", {"baseUrl": f"http://localhost:{porta}/hooks/medilink", "ativo": True, "novoSegredo": True}, admin)
    conferir("sistema, API key e webhook cadastrados (segredos mostrados uma vez)", bool(k.get("chave") and w.get("segredo")))
    servidor, recebidos = webhook_sink.iniciar(porta, w["segredo"])

    # ---- T16: agendamento por API
    quando = (datetime.now(timezone.utc) + timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    corpo = {"medico": {"nome": "Dra. Ana Lima", "chave": "CRM-SMOKE"}, "agendamento": {"chave": "smoke-" + uuid.uuid4().hex[:6], "quando": {"dataHora": quando}},
             "paciente": {"nome": "Maria Souza", "cpf": CPF, "email": "maria@exemplo.test"}, "webhook": {"caminho": "/clinica-42/consultas?tenant=7"}}
    st, ag = chamar("POST", "/api/integracao/v1/agendamentos", corpo, key)
    conferir("agendamento criado por API: devolve a chave da reunião e o link do paciente", st == 201 and ag.get("chaveReuniao") and "/sala/" in ag.get("linkPaciente", ""))
    st2, ag2 = chamar("POST", "/api/integracao/v1/agendamentos", corpo, key)
    conferir("repetir a mesma chave é idempotente", st2 == 200 and ag2["chaveReuniao"] == ag["chaveReuniao"])
    conferir("a resposta não traz CPF, e-mail nem nome", all(x not in json.dumps(ag) for x in ("529", "maria@", "Maria")))
    sala = ag["linkPaciente"].rsplit("/sala/", 1)[1]
    chave = ag["chaveReuniao"]

    # ---- T17: o médico entra por link de uso único
    st, l = chamar("POST", f"/api/integracao/v1/agendamentos/{chave}/acesso-medico", {"nomeMedico": "Dra. Ana Lima", "chaveMedico": "CRM-SMOKE"}, key)
    conferir("o sistema pede o link do médico e não recebe nenhum JWT", st == 200 and "#t=" in l.get("linkMedico", "") and "eyJ" not in json.dumps(l))
    token = l["linkMedico"].split("#t=", 1)[1]
    st, s = chamar("POST", "/api/medico/sessao", {"token": token})
    conferir("o link vira a sessão da consulta", st == 200 and s.get("modo") == "SALA")
    st, _ = chamar("POST", "/api/medico/sessao", {"token": token})
    conferir("o mesmo link não vale duas vezes", st == 403)
    med = {"Authorization": "Bearer " + s["sessao"]}
    consulta = s["consultaId"]
    st, _ = chamar("GET", f"/api/consultas/{uuid.uuid4()}", None, med)
    conferir("a sessão não alcança outra consulta", st == 403)

    # ---- paciente: CPF, termo obrigatório, espera
    _, ent = chamar("POST", f"/api/salas/{sala}/validar-cpf", {"cpf": CPF})
    st, _ = chamar("POST", f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("sem aceitar o termo o paciente não chega à espera", st == 409)
    _, termo = chamar("GET", "/api/termo-consentimento")
    chamar("POST", f"/api/consultas/{consulta}/consentimento", {"tokenEntrada": ent["tokenEntrada"], "aceito": True, "versaoTermo": termo["versao"]})
    st, _ = chamar("POST", f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("aceito o termo, o paciente espera (202)", st == 202)
    st, _ = chamar("POST", f"/api/consultas/{consulta}/aceite", None, med)
    conferir("o médico (pela sessão do link) aceita o paciente", st == 200)
    st, d = chamar("POST", f"/api/consultas/{consulta}/encerrar", None, med)
    conferir("o médico encerra a consulta", st == 200 and d.get("status") == "ENCERRADA")

    # ---- T19: sem áudio capturado, o destino é avisado (assinado) de que não há transcrição
    print("aguardando o webhook (rotina de 5 s)…", flush=True)
    fim = time.time() + 40
    while time.time() < fim and not recebidos:
        time.sleep(1)
    e = recebidos[0] if recebidos else None
    conferir("o webhook chegou assinado e com assinatura válida", e is not None)
    if e:
        conferir("destino = URL base cadastrada + caminho/parâmetros do agendamento", e["caminho"] == "/hooks/medilink/clinica-42/consultas?tenant=7", e["caminho"])
        conferir("evento transcricao.indisponivel com o motivo", e["evento"] == "transcricao.indisponivel" and e["corpo"].get("motivo") == "SEM_AUDIO")
        conferir("o corpo identifica a reunião pela chave e não leva dado do paciente",
                 e["corpo"]["agendamento"]["chaveReuniao"] == chave and all(x not in json.dumps(e["corpo"]) for x in ("529", "maria@", "Maria")))

    # ---- T18: painel e revogação
    st, p = chamar("GET", "/api/admin/painel?dias=1", None, admin)
    conferir("o painel mostra a consulta sem áudio como incidente", st == 200 and any(x["consultaId"] == consulta and x["tipo"] == "SEM_AUDIO" for x in p["incidentes"]))
    conferir("o painel não traz dado de paciente", all(x not in json.dumps(p) for x in ("Maria", "529.982", "maria@")))
    st, entregas = chamar("GET", "/api/admin/webhooks/entregas?status=ENTREGUE", None, admin)
    conferir("a entrega aparece como ENTREGUE no painel", st == 200 and any(x["consultaId"] == consulta for x in entregas))
    chamar("POST", f"/api/admin/chaves/{k['id']}/revogar", None, admin)
    st, _ = chamar("GET", f"/api/integracao/v1/agendamentos/{chave}", None, key)
    conferir("key revogada deixa de funcionar", st == 401)
    servidor.shutdown()
    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
