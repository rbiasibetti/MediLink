#!/usr/bin/env python3
"""Verificação do T02 contra o Prosody real: só entra quem porta token válido.

Abre uma sessão XMPP por BOSH (a mesma porta que o navegador usa) e tenta autenticar. O Prosody lê o
token do query string e rejeita o SASL quando o JWT falta, é inválido ou não é da sala.

Uso: verify_auth.py <sala> <jwt-valido-para-a-sala> [base-url]
Sem dependências além da biblioteca padrão. Certificado autoassinado do dev é aceito de propósito
(somente para localhost).
"""
import re
import ssl
import sys
import urllib.parse
import urllib.request

BASE = sys.argv[3] if len(sys.argv) > 3 else "https://localhost:8443"
NS = "http://jabber.org/protocol/httpbind"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def post(url, body):
    req = urllib.request.Request(url, data=body.encode(), headers={"Content-Type": "text/xml; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=15) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def entrar(sala, token, nick="verif"):
    """Tenta autenticar e entrar na sala. Retorna (resultado, xml_da_presenca).

    resultado é 'ACEITO' ou o motivo da recusa; o xml traz as presenças da sala (papéis dos ocupantes).
    """
    query = {"room": sala}
    if token is not None:
        query["token"] = token
    url = f"{BASE}/http-bind?{urllib.parse.urlencode(query)}"
    rid = [1000]

    def enviar(conteudo="", sid=None, extra=""):
        rid[0] += 1
        attrs = f"rid='{rid[0]}' xmlns='{NS}'" + (f" sid='{sid}'" if sid else "") + extra
        return post(url, f"<body {attrs}>{conteudo}</body>")

    st, xml = post(url, f"<body rid='1000' xmlns='{NS}' to='meet.jitsi' xmlns:xmpp='urn:xmpp:xbosh' "
                        f"xmpp:version='1.0' hold='1' wait='5' ver='1.6'/>")
    sid = re.search(r"sid=['\"]([^'\"]+)['\"]", xml)
    if not sid:
        return f"RECUSADO na abertura (HTTP {st})", ""
    sid = sid.group(1)

    # 1) SASL: o Prosody valida assinatura, emissor, audiência e validade do token.
    st, xml = enviar("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='ANONYMOUS'/>", sid)
    if "<success" not in xml:
        motivo = re.search(r"<failure[^>]*>\s*<([a-z-]+)", xml)
        return f"RECUSADO no login ({motivo.group(1) if motivo else 'HTTP %d' % st})", ""

    # 2) Bind e pedido de conferência ao Jicofo (é ele quem cria a sala, como no cliente real).
    enviar("", sid, " xmpp:restart='true' to='meet.jitsi' xmlns:xmpp='urn:xmpp:xbosh'")
    enviar("<iq type='set' id='b1' xmlns='jabber:client'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/></iq>", sid)
    st, xml = enviar(f"<iq type='set' id='c1' to='focus.meet.jitsi' xmlns='jabber:client'>"
                     f"<conference xmlns='http://jitsi.org/protocol/focus' room='{sala}@muc.meet.jitsi' "
                     f"machine-uid='verify-{nick}'/></iq>", sid)
    for _ in range(6):
        if "ready='true'" in xml or 'ready="true"' in xml:
            break
        if "type='error'" in xml or 'type="error"' in xml:
            erro = re.search(r"<error[^>]*>\s*<([a-z-]+)", xml)
            return f"RECUSADO pelo Jicofo ({erro.group(1) if erro else 'erro'})", ""
        st, xml = enviar("", sid)
    else:
        return "RECUSADO pelo Jicofo (sem resposta)", ""

    # 3) Presença na sala: aqui o Jitsi confere a claim "room" do token.
    st, xml = enviar(f"<presence to='{sala}@muc.meet.jitsi/{nick}' xmlns='jabber:client'>"
                     f"<x xmlns='http://jabber.org/protocol/muc'/></presence>", sid)
    acumulado = xml
    for _ in range(6):
        if "type='error'" in xml or 'type="error"' in xml:
            erro = re.search(r"<error[^>]*>\s*<([a-z-]+)", xml)
            return f"RECUSADO na sala ({erro.group(1) if erro else 'erro'})", ""
        if "muc#user" in acumulado and "code='110'" in acumulado.replace('"', "'"):
            return "ACEITO", acumulado.replace('"', "'")
        st, xml = enviar("", sid)
        acumulado += xml
    if "muc#user" in acumulado:
        return "ACEITO", acumulado.replace('"', "'")
    return "RECUSADO na sala (sem resposta)", ""


def tentar(sala, token):
    return entrar(sala, token)[0]


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    sala, jwt = sys.argv[1], sys.argv[2]
    casos = [
        ("sem token", sala, None, False),
        ("token inválido (lixo)", sala, "isto.nao.eh-um-jwt", False),
        ("token com assinatura adulterada", sala, jwt[:-4] + ("AAAA" if not jwt.endswith("AAAA") else "BBBB"), False),
        ("token válido, mas de outra sala", sala + "-outra", jwt, False),
        ("token válido da sala", sala, jwt, True),
    ]
    falhas = 0
    for nome, s, t, esperado_ok in casos:
        r = tentar(s, t)
        ok = (r == "ACEITO") == esperado_ok
        falhas += not ok
        print(f"[{'OK ' if ok else 'ERRO'}] {nome:<36} -> {r}")
    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
