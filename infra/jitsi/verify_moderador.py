#!/usr/bin/env python3
"""Verificação de segurança: o paciente NUNCA pode ser moderador da sala do Jitsi.

Reproduz o cenário relatado em teste: o médico (owner) e o paciente (member) entram; o médico sai; o paciente
fica sozinho. Com o auto-owner do Jicofo ligado (padrão), o Jicofo promoveria o paciente a owner/moderator ao
ficar sem moderador na sala — e aí ele poderia "encerrar para todos" e reentrar sempre como moderador. Com
jicofo.conference.enable-auto-owner = false (custom-jicofo.conf), o papel vem só do JWT: o paciente continua
participante comum.

Mantém DUAS sessões BOSH vivas ao mesmo tempo (ao contrário de verify_auth.entrar, que entra e sai), para poder
derrubar o médico e observar a presença do paciente mudar (ou não).

Pré-requisitos: Jitsi no ar (dev.sh up) e backend em dev em :8090. Uso:
  verify_moderador.py <usuario:senha-do-medico> [backend=http://localhost:8090]
"""
import base64
import json
import re
import ssl
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

BASE_JITSI = "https://localhost:8443"
NS = "http://jabber.org/protocol/httpbind"
CPF = "529.982.247-25"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


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


class Sessao:
    """Sessão BOSH persistente: abre, autentica com o token, entra na sala e permite poll e término."""

    def __init__(self, sala, token, nick):
        self.sala, self.nick = sala, nick
        self.url = f"{BASE_JITSI}/http-bind?{urllib.parse.urlencode({'room': sala, 'token': token})}"
        self.rid = 1000
        self.sid = None
        self.presencas = ""

    def _post(self, body):
        req = urllib.request.Request(self.url, data=body.encode(), headers={"Content-Type": "text/xml; charset=utf-8"})
        try:
            with urllib.request.urlopen(req, context=CTX, timeout=15) as r:
                return r.read().decode()
        except urllib.error.HTTPError as e:
            return e.read().decode(errors="replace")

    def _env(self, conteudo="", extra=""):
        self.rid += 1
        attrs = f"rid='{self.rid}' xmlns='{NS}'" + (f" sid='{self.sid}'" if self.sid else "") + extra
        xml = self._post(f"<body {attrs}>{conteudo}</body>")
        self.presencas += xml
        return xml

    def entrar(self):
        xml = self._post(f"<body rid='{self.rid}' xmlns='{NS}' to='meet.jitsi' xmlns:xmpp='urn:xmpp:xbosh' "
                         f"xmpp:version='1.0' hold='1' wait='5' ver='1.6'/>")
        m = re.search(r"sid=['\"]([^'\"]+)['\"]", xml)
        if not m:
            return "RECUSADO na abertura"
        self.sid = m.group(1)
        xml = self._env("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='ANONYMOUS'/>")
        if "<success" not in xml:
            return "RECUSADO no login"
        self._env("", " xmpp:restart='true' to='meet.jitsi' xmlns:xmpp='urn:xmpp:xbosh'")
        self._env("<iq type='set' id='b1' xmlns='jabber:client'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/></iq>")
        xml = self._env(f"<iq type='set' id='c1' to='focus.meet.jitsi' xmlns='jabber:client'>"
                        f"<conference xmlns='http://jitsi.org/protocol/focus' room='{self.sala}@muc.meet.jitsi' "
                        f"machine-uid='verif-{self.nick}'/></iq>")
        for _ in range(8):
            if "ready='true'" in xml or 'ready="true"' in xml:
                break
            xml = self._env()
        self._env(f"<presence to='{self.sala}@muc.meet.jitsi/{self.nick}' xmlns='jabber:client'>"
                  f"<x xmlns='http://jabber.org/protocol/muc'/></presence>")
        for _ in range(6):
            if "code='110'" in self.presencas.replace('"', "'"):
                return "ACEITO"
            self._env()
        return "ACEITO" if "muc#user" in self.presencas else "RECUSADO na sala"

    def poll(self, vezes=6, intervalo=1.0):
        """Coleta presenças novas (o servidor empurra as mudanças de affiliation/role por long-poll)."""
        for _ in range(vezes):
            self._env()
            time.sleep(intervalo)

    def terminar(self):
        self._env(extra=" type='terminate'")

    def meu_papel(self):
        """Último (affiliation, role) anunciado para o meu nick (code 110 = 'é você')."""
        resultado = None
        xml = self.presencas.replace('"', "'")
        for pres in re.finditer(r"<presence[^>]*from='[^']*/" + re.escape(self.nick) + r"'.*?</presence>", xml, re.S):
            bloco = pres.group(0)
            item = re.search(r"<item([^>]*)/?>", bloco)
            if item:
                a = dict(re.findall(r"(\w+)='([^']*)'", item.group(1)))
                resultado = (a.get("affiliation"), a.get("role"))
        return resultado


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
    sala = ag["roomName"]
    st, med = api(base, f"/api/agendamentos/{ag['id']}/token-jitsi", None, auth)
    st, ent = api(base, f"/api/salas/{sala}/validar-cpf", {"cpf": CPF})
    with urllib.request.urlopen(base + "/api/termo-consentimento", timeout=20) as r:
        versao = json.loads(r.read().decode())["versao"]
    api(base, f"/api/consultas/{ag['consultaId']}/consentimento", {"tokenEntrada": ent["tokenEntrada"], "aceito": True, "versaoTermo": versao})
    api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    api(base, f"/api/consultas/{ag['consultaId']}/aceite", None, auth)
    st, pac = api(base, f"/api/salas/{sala}/token", {"tokenEntrada": ent["tokenEntrada"]})
    conferir("setup: médico e paciente têm JWT", "jwt" in med and "jwt" in pac)

    s_med = Sessao(sala, med["jwt"], "medico")
    s_pac = Sessao(sala, pac["jwt"], "paciente")
    conferir("médico entra", s_med.entrar() == "ACEITO")
    conferir("paciente entra", s_pac.entrar() == "ACEITO")
    s_med.poll(2, 0.5)
    s_pac.poll(2, 0.5)
    conferir("médico é owner/moderator", s_med.meu_papel() == ("owner", "moderator"), str(s_med.meu_papel()))
    conferir("paciente entra como member/participant", s_pac.meu_papel() == ("member", "participant"), str(s_pac.meu_papel()))

    # O médico SAI. O paciente fica sozinho: é o momento em que o auto-owner o promoveria.
    s_med.terminar()
    s_pac.poll(6, 1.0)
    papel_sozinho = s_pac.meu_papel()
    conferir("paciente NÃO é promovido a moderador quando o médico sai",
             papel_sozinho is not None and papel_sozinho[1] != "moderator" and papel_sozinho[0] != "owner",
             str(papel_sozinho))
    s_pac.terminar()

    # Reentrada após ficar sozinho/encerrar: continua participante comum.
    s_re = Sessao(sala, pac["jwt"], "paciente2")
    conferir("paciente reentra", s_re.entrar() == "ACEITO")
    s_re.poll(3, 1.0)
    papel_re = s_re.meu_papel()
    conferir("paciente que reentra sozinho continua sem moderação",
             papel_re is not None and papel_re[1] != "moderator" and papel_re[0] != "owner", str(papel_re))
    s_re.terminar()

    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
