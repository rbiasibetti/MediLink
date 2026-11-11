#!/usr/bin/env python3
"""Tira as capturas de tela do manual do usuário (docs/manual/img) com o sistema de DESENVOLVIMENTO de verdade.

Percorre as telas do paciente, do médico e do administrador num Chrome headless (protocolo DevTools), com dados
fictícios criados na hora pela API. Câmera e microfone são falsos do próprio Chrome (a imagem de vídeo é um padrão de
teste, não uma pessoa). A única resposta simulada é a do texto da transcrição (o worker de transcrição não é
necessário para o manual); o script a marca no log.

Pré-requisitos (tudo em dev): Jitsi no ar (infra/jitsi/dev.sh up), backend em :8090 iniciado com
MEDILINK_CONSULTA_TOLERANCIA_REABERTURA=0s, `ng serve` em :4200, Redpanda opcional, Chrome instalado e o segredo do TOTP do
administrador de dev em ~/.cache/medilink/smoke-totp (criado por infra/dev/smoke_integracao.py).

Uso: capturar_manual.py [pasta-de-saída]   (padrão: docs/manual/img)
Usa o pacote `websockets` (já presente no Python do sistema ou do venv do worker).
"""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import websockets

AQUI = Path(__file__).resolve().parent
SAIDA = Path(sys.argv[1]) if len(sys.argv) > 1 else AQUI.parent.parent / "docs" / "manual" / "img"
BACKEND = os.environ.get("BACKEND", "http://localhost:8090")
APP = os.environ.get("APP", "http://localhost:4200")
CHROME = os.environ.get("CHROME_BIN", "google-chrome")
PORTA = 9333
ADMIN_DEV = ("admin@medilink.local", os.environ.get("MEDILINK_ADMIN_SENHA", "dev-only-admin-change-me-1"))
ARQ_TOTP = Path.home() / ".cache" / "medilink" / "smoke-totp"
SENHA_MEDICO = "senha-do-manual-123"
SENHA_ADMIN = "senha-do-manual-admin-1"
CPF = "529.982.247-25"
DESKTOP = (1280, 800)
CELULAR = (390, 844)


# ---------------------------------------------------------------- utilidades de API / TOTP
def totp(segredo, passo=None):
    alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    bits = "".join(f"{alfabeto.index(c):05b}" for c in segredo.strip().upper())
    chave = bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits) - len(bits) % 8, 8))
    passo = int(time.time() // 30) if passo is None else passo
    h = hmac.new(chave, struct.pack(">Q", passo), hashlib.sha1).digest()
    o = h[-1] & 15
    return f"{((struct.unpack('>I', h[o:o + 4])[0] & 0x7fffffff) % 1_000_000):06d}"


def api(metodo, caminho, corpo=None, token=None):
    req = urllib.request.Request(BACKEND + caminho, method=metodo, data=None if corpo is None else json.dumps(corpo).encode())
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            dados = r.read()
            return r.status, (json.loads(dados) if dados else None)
    except urllib.error.HTTPError as e:
        dados = e.read()
        return e.code, (json.loads(dados) if dados else None)


def exigir(st, esperado=(200, 201, 204)):
    if st[0] not in esperado:
        sys.exit(f"falha na API: {st}")
    return st[1]


# ---------------------------------------------------------------- Chrome via DevTools
class Aba:
    def __init__(self, ws, largura, altura, celular=False):
        self.ws, self.largura, self.altura, self.celular = ws, largura, altura, celular
        self.prox = 0
        self.pend = {}
        self.eventos = []
        self.tarefa = None
        self.interceptar = {}

    @classmethod
    async def nova(cls, largura, altura, celular=False):
        req = urllib.request.Request(f"http://localhost:{PORTA}/json/new?about:blank", method="PUT")
        info = json.loads(urllib.request.urlopen(req).read())
        ws = await websockets.connect(info["webSocketDebuggerUrl"], max_size=64 * 1024 * 1024)
        aba = cls(ws, largura, altura, celular)
        aba.id_alvo = info["id"]
        aba.tarefa = asyncio.create_task(aba._ler())
        await aba.cmd("Page.enable")
        await aba.cmd("Runtime.enable")
        await aba.cmd("Emulation.setDeviceMetricsOverride", width=largura, height=altura, deviceScaleFactor=1, mobile=celular)
        return aba

    async def _ler(self):
        async for bruto in self.ws:
            m = json.loads(bruto)
            if "id" in m and m["id"] in self.pend:
                self.pend.pop(m["id"]).set_result(m)
            elif m.get("method") == "Fetch.requestPaused":
                asyncio.create_task(self._atender(m["params"]))
            else:
                self.eventos.append(m)

    async def _atender(self, p):
        url = p["request"]["url"].split("?")[0]
        metodo = p["request"]["method"]
        for (trecho, exato, so_metodo), corpo in self.interceptar.items():
            if (url.endswith(trecho) if exato else trecho in url) and (so_metodo is None or so_metodo == metodo):
                valor = corpo() if callable(corpo) else corpo
                await self.cmd("Fetch.fulfillRequest", requestId=p["requestId"], responseCode=200,
                               responseHeaders=[{"name": "Content-Type", "value": "application/json"}],
                               body=base64.b64encode(json.dumps(valor).encode()).decode())
                return
        await self.cmd("Fetch.continueRequest", requestId=p["requestId"])

    async def simular(self, trecho_da_url, corpo, exato=False, metodo=None):
        """Responde com `corpo` (JSON, 200; ou o que a função devolver) às chamadas cuja URL contém (ou termina com) o trecho."""
        if not self.interceptar:
            await self.cmd("Fetch.enable", patterns=[{"urlPattern": "*/api/*"}])
        self.interceptar[(trecho_da_url, exato, metodo)] = corpo

    async def cmd(self, metodo, **params):
        self.prox += 1
        fut = asyncio.get_running_loop().create_future()
        self.pend[self.prox] = fut
        await self.ws.send(json.dumps({"id": self.prox, "method": metodo, "params": params}))
        resp = await fut
        if "error" in resp:
            raise RuntimeError(f"{metodo}: {resp['error']}")
        return resp.get("result", {})

    async def js(self, expressao, contexto=None):
        extra = {} if contexto is None else {"contextId": contexto}
        r = await self.cmd("Runtime.evaluate", expression=expressao, returnByValue=True, awaitPromise=True, **extra)
        if "exceptionDetails" in r:
            raise RuntimeError(r["exceptionDetails"].get("exception", {}).get("description", "erro de JS"))
        return r["result"].get("value")

    async def ir(self, url, espera=1.5):
        await self.cmd("Page.navigate", url=url)
        await asyncio.sleep(espera)

    async def esperar(self, condicao_js, limite=30, nome=""):
        fim = time.time() + limite
        while time.time() < fim:
            try:
                if await self.js(condicao_js):
                    return
            except Exception:
                pass
            await asyncio.sleep(0.4)
        raise TimeoutError(f"tempo esgotado esperando: {nome or condicao_js}")

    async def esperar_texto(self, texto, limite=30):
        await self.esperar(f"document.body.innerText.includes({json.dumps(texto)})", limite, texto)

    async def digitar(self, seletor, valor):
        await self.js(f"""(()=>{{const e=document.querySelector({json.dumps(seletor)});e.focus();e.value={json.dumps(valor)};
            e.dispatchEvent(new Event('input',{{bubbles:true}}));e.dispatchEvent(new Event('change',{{bubbles:true}}));}})()""")

    async def clicar(self, texto, seletor="button,a"):
        ok = await self.js(f"""(()=>{{const b=[...document.querySelectorAll({json.dumps(seletor)})].find(x=>x.textContent.trim().includes({json.dumps(texto)}));
            if(!b)return false;b.click();return true;}})()""")
        if not ok:
            raise RuntimeError(f"controle não encontrado: {texto}")
        await asyncio.sleep(0.6)

    async def marcar(self, seletor):
        await self.js(f"document.querySelector({json.dumps(seletor)}).click()")
        await asyncio.sleep(0.3)

    async def foto(self, nome, completo=False, altura_max=2400):
        SAIDA.mkdir(parents=True, exist_ok=True)
        await asyncio.sleep(0.4)
        params = {"format": "png"}
        if completo:
            m = await self.cmd("Page.getLayoutMetrics")
            h = min(int(m["cssContentSize"]["height"]), altura_max)
            await self.cmd("Emulation.setDeviceMetricsOverride", width=self.largura, height=max(h, self.altura), deviceScaleFactor=1, mobile=self.celular)
            await asyncio.sleep(0.3)
        r = await self.cmd("Page.captureScreenshot", **params)
        if completo:
            await self.cmd("Emulation.setDeviceMetricsOverride", width=self.largura, height=self.altura, deviceScaleFactor=1, mobile=self.celular)
        (SAIDA / f"{nome}.png").write_bytes(base64.b64decode(r["data"]))
        print(f"  captura: {nome}.png", flush=True)

    async def mouse(self, x, y, clicar=False):
        """Move (e opcionalmente clica) com o mouse de verdade: funciona também dentro do iframe do Jitsi."""
        await self.cmd("Input.dispatchMouseEvent", type="mouseMoved", x=x, y=y)
        if clicar:
            await self.cmd("Input.dispatchMouseEvent", type="mousePressed", x=x, y=y, button="left", clickCount=1)
            await self.cmd("Input.dispatchMouseEvent", type="mouseReleased", x=x, y=y, button="left", clickCount=1)

    async def mostrar_barra(self):
        """O Jitsi esconde a barra de botões sem movimento do mouse: vários movimentos sobre a parte de baixo da sala."""
        for i in range(12):
            await self.mouse(500 + i * 25, 640 + (i % 3) * 20)
            await asyncio.sleep(0.15)

    async def contexto_do_iframe(self):
        """Contexto de JS do iframe do Jitsi (mesmo site, portas diferentes), para clicar nos botões dele."""
        arvore = (await self.cmd("Page.getFrameTree"))["frameTree"]
        filhos = arvore.get("childFrames", [])
        if not filhos:
            return None
        mundo = await self.cmd("Page.createIsolatedWorld", frameId=filhos[0]["frame"]["id"], worldName="manual")
        return mundo["executionContextId"]

    async def fechar(self):
        self.tarefa.cancel()
        await self.ws.close()


def iniciar_chrome(perfil):
    args = [CHROME, "--headless=new", f"--remote-debugging-port={PORTA}", f"--user-data-dir={perfil}", "--no-first-run",
            "--no-default-browser-check", "--ignore-certificate-errors", "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream", "--autoplay-policy=no-user-gesture-required", "--lang=pt-BR",
            "--hide-scrollbars", "--force-color-profile=srgb", "--disable-gpu", "about:blank"]
    p = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        try:
            urllib.request.urlopen(f"http://localhost:{PORTA}/json/version", timeout=1)
            return p
        except Exception:
            time.sleep(0.3)
    p.kill()
    sys.exit("o Chrome não abriu a porta de depuração")


# ---------------------------------------------------------------- dados fictícios
def preparar_dados():
    print("preparando dados fictícios…", flush=True)
    segredo = ARQ_TOTP.read_text().strip()
    st, login = api("POST", "/api/admin/login", {"email": ADMIN_DEV[0], "senha": ADMIN_DEV[1], "codigo": totp(segredo, int(time.time() // 30) + 1)})
    if st != 200:
        sys.exit(f"login do administrador de dev falhou ({st}); rode antes infra/dev/smoke_integracao.py")
    adm = login["token"]
    sufixo = str(int(time.time()))[-6:]
    admin_ui = f"manual-{sufixo}@exemplo.test"
    exigir(api("POST", "/api/admin/administradores", {"email": admin_ui, "senha": SENHA_ADMIN}, adm))
    medico_email = f"ana.lima.{sufixo}@exemplo.test"
    exigir(api("POST", "/api/admin/medicos", {"nome": "Dra. Ana Lima", "identificadorProfissional": "CRM-SP 123456", "email": medico_email,
                                              "senha": SENHA_MEDICO, "exigirTotp": False}, adm))
    totp_email = f"joao.pereira.{sufixo}@exemplo.test"
    exigir(api("POST", "/api/admin/medicos", {"nome": "Dr. João Pereira", "identificadorProfissional": "CRM-RS 4321", "email": totp_email,
                                              "senha": SENHA_MEDICO, "exigirTotp": True}, adm))
    exigir(api("POST", "/api/admin/medicos", {"nome": "Dra. Carla Mendes", "identificadorProfissional": "CRM-MG 98765", "email": f"carla.mendes.{sufixo}@exemplo.test",
                                              "senha": SENHA_MEDICO, "exigirTotp": False}, adm))
    st, integ = api("POST", "/api/admin/integradores", {"nome": f"Sistema de Agenda Exemplo {sufixo}"}, adm)
    exigir((st, integ))
    return {"admin_email": admin_ui, "medico_email": medico_email, "totp_email": totp_email, "integrador": integ["nome"], "integrador_id": integ["id"]}


def agendar(token_medico, nome, cpf, quando, email=None):
    corpo = {"pacienteNome": nome, "pacienteCpf": cpf, "dataHora": quando.isoformat(), "cpfValidationEnabled": True}
    if email:
        corpo["pacienteEmail"] = email
    return exigir(api("POST", "/api/agendamentos", corpo, token_medico))


def entrega(i, status, evento, tentativas, http, erro, minutos_atras):
    agora = datetime.now(timezone.utc)
    return {"id": f"e0000000-0000-4000-8000-00000000000{i}", "evento": evento, "motivo": None, "status": status, "tentativas": tentativas,
            "ultimoStatusHttp": http, "ultimoErro": erro, "criadoEm": (agora - timedelta(minutes=minutos_atras)).isoformat(),
            "proximaTentativaEm": (agora + timedelta(minutes=5)).isoformat() if status == "PENDENTE" else None,
            "entregueEm": (agora - timedelta(minutes=minutos_atras - 1)).isoformat() if status == "ENTREGUE" else None,
            "consultaId": f"c0000000-0000-4000-8000-00000000000{i}", "integradorId": "10000000-0000-4000-8000-000000000001"}


async def administrador(dados):
    """Telas do administrador. As LISTAS (painel, médicos, sistemas e entregas de webhook) são simuladas com dados fictícios
    enxutos, porque o banco de desenvolvimento acumula lixo dos testes; o restante (login, TOTP, formulários e key) é real. O termo
    e o cadastro de médico também são simulados, para a captura não deixar rascunhos nem contas no banco."""
    print("administrador…", flush=True)
    adm = await Aba.nova(*DESKTOP)
    agora = datetime.now(timezone.utc)
    medicos = [
        {"id": "20000000-0000-4000-8000-000000000001", "nome": "Dra. Ana Lima", "identificadorProfissional": "CRM-SP 123456", "email": "ana.lima@clinica.exemplo",
         "ativo": True, "temSenha": True, "totpHabilitado": True, "totpConfirmado": True, "deIntegracao": False},
        {"id": "20000000-0000-4000-8000-000000000002", "nome": "Dr. João Pereira", "identificadorProfissional": "CRM-RS 4321", "email": "joao.pereira@clinica.exemplo",
         "ativo": True, "temSenha": True, "totpHabilitado": True, "totpConfirmado": False, "deIntegracao": False},
        {"id": "20000000-0000-4000-8000-000000000003", "nome": "Dra. Carla Mendes", "identificadorProfissional": "CRM-MG 98765", "email": "carla.mendes@clinica.exemplo",
         "ativo": False, "temSenha": True, "totpHabilitado": False, "totpConfirmado": False, "deIntegracao": False},
        {"id": "20000000-0000-4000-8000-000000000004", "nome": "Dr. Paulo Souza", "identificadorProfissional": "CRM-BA 11223", "email": "ext-1@integracao.medilink.invalid",
         "ativo": True, "temSenha": False, "totpHabilitado": False, "totpConfirmado": False, "deIntegracao": True}]
    integradores = [
        {"id": "10000000-0000-4000-8000-000000000001", "nome": "Sistema de Prontuário Exemplo", "ativo": True, "webhookBaseUrl": "https://api.cliente.com.br/hooks/medilink",
         "webhookAtivo": True, "webhookTemSegredo": True, "criadoEm": (agora - timedelta(days=40)).isoformat()},
        {"id": "10000000-0000-4000-8000-000000000002", "nome": "Agenda Online Exemplo", "ativo": True, "webhookBaseUrl": None, "webhookAtivo": False,
         "webhookTemSegredo": False, "criadoEm": (agora - timedelta(days=12)).isoformat()}]
    painel = {"geradoEm": agora.isoformat(), "periodoDias": 30,
              "agendamentos": {"porStatus": {"AGENDADO": 118, "CANCELADO": 9}, "criadosNoPeriodo": 127,
                               "porSistema": [{"sistema": "Sistema de Prontuário Exemplo", "total": 84, "noPeriodo": 84},
                                              {"sistema": "Agenda Online Exemplo", "total": 31, "noPeriodo": 31},
                                              {"sistema": "(médicos locais, sem sistema integrado)", "total": 12, "noPeriodo": 12}]},
              "consultas": {"porStatus": {"CRIADA": 9, "AGUARDANDO_PACIENTE": 1, "EM_ANDAMENTO": 2, "ENCERRADA": 115}, "encerradasNoPeriodo": 115},
              "transcricao": {"jobsPorStatus": {"PENDENTE": 2, "ENFILEIRADO": 1, "CONCLUIDO": 109, "SEM_AUDIO": 2, "FALHA_NO_WORKER": 1}, "transcricoesGravadas": 109,
                              "audioAindaNoDisco": 4, "audioMaisAntigoDesde": (agora - timedelta(hours=3)).isoformat()},
              "webhooks": {"ENTREGUE": 106, "PENDENTE": 1, "FALHA": 2},
              "incidentes": [{"consultaId": "c0000000-0000-4000-8000-000000000003", "tipo": "FALHA_NO_WORKER", "desde": (agora - timedelta(hours=5)).isoformat(), "erro": "RuntimeError"},
                             {"consultaId": "c0000000-0000-4000-8000-000000000004", "tipo": "SEM_AUDIO", "desde": (agora - timedelta(days=1)).isoformat(), "erro": None}]}
    st, vigente = api("GET", "/api/termo-consentimento")
    exigir((st, vigente))
    base_termo = {"texto": vigente["texto"], "hash": vigente.get("hash") or "0" * 64, "criadoEm": (agora - timedelta(days=10)).isoformat()}
    v1 = {"id": "30000000-0000-4000-8000-000000000001", "versao": "v1", "status": "ARQUIVADO", "criadoPor": "sistema", "publicadoEm": None, **base_termo}
    v2 = {"id": "30000000-0000-4000-8000-000000000002", "versao": "v2", "status": "VIGENTE", "criadoPor": "sistema", "publicadoEm": (agora - timedelta(days=10)).isoformat(), **base_termo}
    v3 = {"id": "30000000-0000-4000-8000-000000000003", "versao": "v3", "status": "RASCUNHO", "criadoPor": "admin@clinica.exemplo", "publicadoEm": None, **base_termo}
    termos = [v1, v2]
    def novo_rascunho():
        if v3 not in termos:
            termos.insert(0, v3)
        return v3
    await adm.simular("/api/admin/termos", lambda: list(termos), exato=True, metodo="GET")
    await adm.simular("/api/admin/termos", novo_rascunho, exato=True, metodo="POST")
    await adm.simular("/api/admin/painel", painel, metodo="GET")
    await adm.simular("/api/admin/medicos", lambda: medicos, exato=True, metodo="GET")
    await adm.simular("/api/admin/integradores", lambda: integradores, exato=True, metodo="GET")
    await adm.simular("/api/admin/webhooks/entregas", [
        entrega(1, "ENTREGUE", "transcricao.concluida", 1, 200, None, 25),
        entrega(2, "PENDENTE", "transcricao.concluida", 2, 503, "HTTP 503", 12),
        entrega(3, "FALHA", "transcricao.indisponivel", 7, 400, "HTTP 400", 600)], metodo="GET")
    await adm.ir(f"{APP}/admin")
    await adm.esperar_texto("Administração")
    await adm.foto("a01-login")
    await adm.digitar("#email", dados["admin_email"])
    await adm.digitar("#senha", SENHA_ADMIN)
    await adm.clicar("Entrar")
    await adm.esperar_texto("Primeiro acesso")
    await adm.foto("a02-primeiro-acesso-totp")
    segredo = await adm.js("document.querySelector('[role=note] code').textContent")
    await adm.digitar("#codigo", totp(segredo))
    await adm.clicar("Entrar")
    await adm.esperar_texto("Agendamentos", 20)
    await asyncio.sleep(1)
    await adm.foto("a03-painel-andamento", completo=True)

    await adm.clicar("Médicos", "nav button")
    await adm.esperar_texto("Novo médico")
    await adm.digitar("#mnome", "Dr. Ricardo Nunes")
    await adm.digitar("#mid", "CRM-RJ 55555")
    await adm.digitar("#memail", "ricardo.nunes@clinica.exemplo")
    await adm.digitar("#msenha", "uma-senha-inicial-123")
    await adm.marcar("input[name=mtotp]")
    await adm.foto("a04-medicos-cadastro", completo=True)
    ricardo = {"id": "20000000-0000-4000-8000-000000000005", "nome": "Dr. Ricardo Nunes", "identificadorProfissional": "CRM-RJ 55555",
               "email": "ricardo.nunes@clinica.exemplo", "ativo": True, "temSenha": True, "totpHabilitado": True, "totpConfirmado": False, "deIntegracao": False}
    medicos.append(ricardo)
    await adm.simular("/api/admin/medicos", ricardo, exato=True, metodo="POST")  # o cadastro não cria nada de verdade no banco
    await adm.clicar("Cadastrar")
    await adm.esperar_texto("Ricardo Nunes")
    await adm.foto("a05-medicos-lista", completo=True)
    await adm.clicar("Redefinir senha")
    await adm.esperar_texto("Nova senha de")
    await adm.foto("a06-medicos-redefinir-senha", completo=True)

    # O sistema criado de verdade nesta execução aparece na lista simulada com um nome de exibição amigável (mesmo id real).
    nome_real = "Hospital Modelo (integração)"
    integradores.append({"id": dados["integrador_id"], "nome": nome_real, "ativo": True, "webhookBaseUrl": None, "webhookAtivo": False,
                         "webhookTemSegredo": False, "criadoEm": agora.isoformat()})
    await adm.clicar("Sistemas e keys", "nav button")
    await adm.esperar_texto("Sistemas integrados")
    await adm.esperar_texto(nome_real)
    await adm.digitar("input[name=nome]", "Clínica Modelo")
    await adm.foto("a07-sistemas-lista", completo=True)
    await adm.js(f"""(()=>{{const l=[...document.querySelectorAll('tbody tr')].find(t=>t.textContent.includes({json.dumps(nome_real)}));
        [...l.querySelectorAll('button')].find(b=>b.textContent.includes('Abrir')).click();}})()""")
    await adm.esperar_texto("API keys")
    await adm.digitar("input[name=nomeKey]", "produção")
    await adm.clicar("Gerar key")
    await adm.esperar_texto("não será mostrado de novo")
    # A key de verdade é de desenvolvimento, mas não vai para o repositório: a captura mostra um valor de exemplo.
    await adm.js("""(()=>{const c=document.querySelector('[role=status] code');
        c.textContent='mlk_0123456789abcdef0123456789abcdef_EXEMPLO-NAO-USE-ESTE-VALOR';})()""")
    await adm.foto("a08-sistemas-key-criada", completo=True)
    await adm.clicar("Já guardei")
    await adm.digitar("#base", "https://api.cliente.com.br/hooks/medilink")
    await adm.foto("a09-sistemas-webhook", completo=True)

    await adm.clicar("Termo de consentimento", "nav button")
    await adm.esperar_texto("Nova versão")
    await adm.foto("a10-termo-versoes", completo=True)
    await adm.clicar("Nova versão")
    await adm.esperar_texto("RASCUNHO")
    await adm.foto("a11-termo-rascunho", completo=True)

    await adm.clicar("Webhooks", "nav button")
    await adm.esperar_texto("Entregas de webhook")
    await asyncio.sleep(1)
    await adm.foto("a12-webhooks-entregas", completo=True)
    await adm.fechar()


async def main():
    dados = preparar_dados()
    if os.environ.get("PARTES") == "admin":  # só as telas do administrador (mais rápido)
        perfil = tempfile.mkdtemp(prefix="medilink-manual-")
        chrome = iniciar_chrome(perfil)
        try:
            await administrador(dados)
        finally:
            chrome.terminate()
            shutil.rmtree(perfil, ignore_errors=True)
        return
    st, sessao = api("POST", "/api/medico/login", {"email": dados["medico_email"], "senha": SENHA_MEDICO})
    token_medico = exigir((st, sessao))["token"]
    agora = datetime.now(timezone.utc)
    # Consultas para a lista do médico (a primeira é a que percorre a jornada completa com o Jitsi).
    principal = agendar(token_medico, "Maria Souza", CPF, agora + timedelta(minutes=5), "maria.souza@exemplo.test")
    agendar(token_medico, "José Almeida", "111.444.777-35", agora + timedelta(days=1, hours=2))
    agendar(token_medico, "Fernanda Costa", "390.533.447-05", agora + timedelta(days=2, hours=1))
    sala = principal["roomName"]

    perfil = tempfile.mkdtemp(prefix="medilink-manual-")
    chrome = iniciar_chrome(perfil)
    try:
        # ------------------------------------------------ paciente (celular)
        print("paciente…", flush=True)
        pac = await Aba.nova(*CELULAR, celular=True)
        await pac.ir(f"{APP}/sala/consulta-inexistente-0000")
        await pac.esperar_texto("Consulta não encontrada")
        await pac.foto("p00-link-invalido")
        await pac.ir(f"{APP}/sala/{sala}")
        await pac.esperar_texto("confirme seu CPF")
        await pac.foto("p01-confirmar-cpf")
        await pac.digitar("#cpf", "123.456.789-09")
        await pac.clicar("Continuar")
        await pac.esperar("document.querySelector('[role=alert]')", 10, "erro de CPF")
        await pac.foto("p02-cpf-incorreto")
        await pac.digitar("#cpf", CPF)
        await pac.clicar("Continuar")
        await pac.esperar_texto("Antes de entrar")
        await pac.foto("p03-termo", completo=True)

        # ------------------------------------------------ médico (login com e-mail e senha)
        print("médico…", flush=True)
        med = await Aba.nova(*DESKTOP)
        await med.ir(f"{APP}/medico")
        await med.esperar_texto("Acesso do médico")
        await med.foto("m01-login")
        await med.digitar("#usuario", dados["medico_email"])
        await med.digitar("#senha", "senha-errada-123")
        await med.clicar("Entrar")
        await med.esperar("document.querySelector('[role=alert]')", 10, "erro de login")
        await med.foto("m02-login-recusado")
        # médico com TOTP exigido: primeiro acesso mostra o segredo
        await med.ir(f"{APP}/medico")
        await med.esperar_texto("Acesso do médico")
        await med.digitar("#usuario", dados["totp_email"])
        await med.digitar("#senha", SENHA_MEDICO)
        await med.clicar("Entrar")
        await med.esperar_texto("Cadastre este segredo")
        await med.foto("m03-login-totp-primeiro-acesso")

        await med.ir(f"{APP}/medico")
        await med.esperar_texto("Acesso do médico")
        await med.digitar("#usuario", dados["medico_email"])
        await med.digitar("#senha", SENHA_MEDICO)
        await med.clicar("Entrar")
        await med.esperar_texto("Minhas consultas")
        await med.esperar_texto("Maria Souza")
        await med.foto("m04-minhas-consultas")

        await med.clicar("Agendar", "nav a")
        await med.esperar_texto("Novo agendamento")
        await med.digitar("#nome", "Paulo Ribeiro")
        await med.digitar("#cpf", "168.995.350-09")
        await med.digitar("#email", "paulo.ribeiro@exemplo.test")
        amanha = (datetime.now() + timedelta(days=3)).replace(hour=14, minute=30).strftime("%Y-%m-%dT%H:%M")
        await med.digitar("#quando", amanha)
        await med.foto("m05-novo-agendamento")
        await med.clicar("Agendar", "button[type=submit]")
        await med.esperar_texto("Agendamento criado")
        await med.foto("m06-agendamento-criado", completo=True)

        # ------------------------------------------------ sala: paciente na espera, médico aceita
        await pac.clicar("Li e aceito")
        await pac.esperar_texto("Sala de espera")
        await pac.foto("p04-sala-de-espera")

        await med.ir(f"{APP}/medico/consulta/{principal['id']}", espera=2)
        await med.esperar_texto("Paciente aguardando", 40)
        await med.esperar("[...document.querySelectorAll('button')].some(b=>b.textContent.includes('Aceitar paciente')&&!b.disabled)", 60, "botão Aceitar habilitado")
        await asyncio.sleep(4)
        await med.foto("m07-sala-paciente-aguardando")
        await med.clicar("Aceitar paciente")
        await med.esperar_texto("Consulta em andamento", 30)
        await pac.esperar("document.body.innerText.includes('Consulta registrada')", 60, "paciente na sala")
        await asyncio.sleep(10)  # deixa o vídeo de teste aparecer
        await med.mostrar_barra()
        await asyncio.sleep(0.8)
        await med.foto("m08-sala-em-andamento")
        await pac.mostrar_barra()
        await asyncio.sleep(0.8)
        await pac.foto("p05-sala-paciente")

        # médico sai da sala: clica no botão vermelho "desligar" da barra do Jitsi
        desligar = os.environ.get("DESLIGAR_XY")
        if not desligar:
            await med.mostrar_barra()
            await med.foto("debug-barra-jitsi")
            sys.exit("defina DESLIGAR_XY=x,y (posição do botão desligar vista em debug-barra-jitsi.png)")
        x, y = (int(v) for v in desligar.split(","))
        await med.mostrar_barra()
        await med.mouse(x, y)
        await asyncio.sleep(0.5)
        await med.mouse(x, y, clicar=True)
        await asyncio.sleep(1.0)
        await med.foto("m09-sair-ou-terminar")  # o botão desligar do moderador abre este menu
        await med.mouse(x, y - 69, clicar=True)  # "Sair da reunião"
        try:
            await med.esperar_texto("Consulta encerrada", 40)
        except TimeoutError:
            await med.foto("debug-pos-desligar")
            sys.exit("a página não mostrou 'Consulta encerrada': veja debug-pos-desligar.png. Texto: " + str(await med.js("document.body.innerText"))[:300])
        await med.foto("m10-consulta-encerrada")

        # ------------------------------------------------ lista e transcrição (texto de exemplo simulado)
        await med.ir(f"{APP}/medico/consultas")
        await med.esperar_texto("Minhas consultas")
        await med.esperar_texto("Maria Souza")
        await med.foto("m11-consultas-com-transcricao")
        await med.simular("/transcricao", {
            "consultaId": principal["consultaId"], "idioma": "pt", "duracaoMs": 74000, "criadaEm": agora.isoformat(), "texto": "",
            "segmentos": [
                {"papel": "MEDICO", "inicioMs": 2000, "fimMs": 6500, "texto": "Bom dia, Maria. Como você está se sentindo hoje?"},
                {"papel": "PACIENTE", "inicioMs": 7200, "fimMs": 14800, "texto": "Bom dia, doutora. Ainda sinto um pouco de dor de cabeça, principalmente à tarde."},
                {"papel": "MEDICO", "inicioMs": 16000, "fimMs": 24500, "texto": "Entendi. Você conseguiu medir a pressão nos últimos dias?"},
                {"papel": "PACIENTE", "inicioMs": 25600, "fimMs": 31000, "texto": "Medi ontem: estava em doze por oito."}]})
        await med.ir(f"{APP}/medico/transcricao/{principal['consultaId']}")
        await med.esperar_texto("Bom dia, Maria")
        await med.foto("m12-transcricao")
        print("  (a transcrição da captura m12 é texto de exemplo simulado)", flush=True)

        await med.ir(f"{APP}/medico/acesso#t=link-de-exemplo-expirado")
        await med.esperar("document.body.innerText.length>20", 15, "tela do link")
        await asyncio.sleep(1.5)
        await med.foto("m13-link-invalido")

        await administrador(dados)
        print("capturas concluídas em", SAIDA, flush=True)
    finally:
        chrome.terminate()
        shutil.rmtree(perfil, ignore_errors=True)


if __name__ == "__main__":
    asyncio.run(main())
