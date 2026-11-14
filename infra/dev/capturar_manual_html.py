#!/usr/bin/env python3
"""Capturas de tela do manual HTML (docs/manual/img), SEM as cenas do Jitsi.

Reaproveita a infraestrutura de infra/dev/capturar_manual.py (Chrome headless via DevTools, dados
fictícios pela API). Cobre: acesso do paciente até a sala de espera, área do médico (login, lista com
ícones de ação, modais de link e de cancelamento, agendamento, transcrição) e a área administrativa
completa (andamento com gráficos e paginação, médicos, sistemas em modal, termo, webhooks e SMTP).

Pré-requisitos: backend em :8090, ng serve em :4200, segredo TOTP do administrador de dev em
~/.cache/medilink/smoke-totp. O Jitsi NÃO é necessário.

Uso: capturar_manual_html.py [pasta-de-saída]   (padrão: docs/manual/img)
"""
import asyncio
import json
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import capturar_manual as base
from capturar_manual import Aba, api, exigir, iniciar_chrome, preparar_dados, totp, agendar, APP, SENHA_MEDICO, SENHA_ADMIN, CPF, DESKTOP, CELULAR

import shutil
import tempfile

SAIDA = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent.parent / "docs" / "manual" / "img"
base.SAIDA = SAIDA


async def clicar_aria(aba, rotulo, indice=0):
    """Clica num botão/link identificado pelo aria-label (os ícones de ação não têm texto)."""
    ok = await aba.js(f"""(()=>{{const l=[...document.querySelectorAll('[aria-label]')].filter(x=>x.getAttribute('aria-label')==={json.dumps(rotulo)});
        if(l.length<={indice})return false;l[{indice}].click();return true;}})()""")
    if not ok:
        raise RuntimeError(f"controle não encontrado (aria-label): {rotulo}")
    await asyncio.sleep(0.6)


async def paciente(sala):
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
    await pac.clicar("Li e aceito")
    await pac.esperar_texto("Sala de espera")
    await pac.foto("p04-sala-de-espera")
    await pac.fechar()


async def medico(dados, principal):
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
    await med.foto("m04-minhas-consultas", completo=True)

    # modal "Rever o link do paciente"
    await clicar_aria(med, "Rever o link do paciente")
    await med.esperar_texto("Link do paciente")
    await med.foto("m14-link-do-paciente")
    await med.clicar("Fechar")

    # modal de cancelamento (confirma de verdade: a consulta de Fernanda fica "Cancelada" na lista)
    await clicar_aria(med, "Cancelar consulta", indice=2)
    await med.esperar_texto("O link do paciente deixará de funcionar")
    await med.foto("m15-cancelar-consulta")
    await med.clicar("Cancelar a consulta")
    await med.esperar_texto("cancelada.")
    await med.foto("m16-consulta-cancelada", completo=True)

    # novo agendamento
    await med.clicar("Agendar consulta", "nav a")
    await med.esperar_texto("Novo agendamento")
    await med.digitar("#nome", "Paulo Ribeiro")
    await med.digitar("#cpf", "168.995.350-09")
    await med.digitar("#email", "paulo.ribeiro@exemplo.test")
    quando = (datetime.now() + timedelta(days=3)).replace(hour=14, minute=30).strftime("%Y-%m-%dT%H:%M")
    await med.digitar("#quando", quando)
    await med.foto("m05-novo-agendamento", completo=True)
    await med.clicar("Agendar", "button[type=submit]")
    await med.esperar_texto("Agendamento criado")
    await med.foto("m06-agendamento-criado", completo=True)

    # transcrição (texto de exemplo simulado: o worker não é necessário para o manual)
    agora = datetime.now(timezone.utc)
    await med.simular("/transcricao", {
        "consultaId": principal["consultaId"], "idioma": "pt", "duracaoMs": 74000, "criadaEm": agora.isoformat(), "texto": "",
        "segmentos": [
            {"papel": "MEDICO", "inicioMs": 2000, "fimMs": 6500, "texto": "Bom dia, Maria. Como você está se sentindo hoje?"},
            {"papel": "PACIENTE", "inicioMs": 7200, "fimMs": 14800, "texto": "Bom dia, doutora. Ainda sinto um pouco de dor de cabeça, principalmente à tarde."},
            {"papel": "MEDICO", "inicioMs": 16000, "fimMs": 24500, "texto": "Entendi. Você conseguiu medir a pressão nos últimos dias?"},
            {"papel": "PACIENTE", "inicioMs": 25600, "fimMs": 31000, "texto": "Medi ontem: estava em doze por oito."}]})
    await med.ir(f"{APP}/medico/transcricao/{principal['consultaId']}")
    await med.esperar_texto("Bom dia, Maria")
    await med.foto("m12-transcricao", completo=True)
    print("  (a transcrição da captura m12 é texto de exemplo simulado)", flush=True)

    await med.ir(f"{APP}/medico/acesso#t=link-de-exemplo-expirado")
    await med.esperar("document.body.innerText.length>20", 15, "tela do link")
    await asyncio.sleep(1.5)
    await med.foto("m13-link-invalido")
    await med.fechar()


async def administrador(dados):
    """Listas simuladas com dados fictícios enxutos (o banco de dev acumula lixo dos testes);
    login, TOTP, formulários, modal do sistema e SMTP são reais."""
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
    sistemas_painel = [
        {"sistema": "Sistema de Prontuário Exemplo", "total": 84, "noPeriodo": 84},
        {"sistema": "Agenda Online Exemplo", "total": 31, "noPeriodo": 31},
        {"sistema": "Hospital Modelo", "total": 22, "noPeriodo": 20},
        {"sistema": "Clínica Vida", "total": 18, "noPeriodo": 15},
        {"sistema": "Centro Médico Sul", "total": 14, "noPeriodo": 12},
        {"sistema": "Rede Saúde Norte", "total": 9, "noPeriodo": 9},
        {"sistema": "(médicos locais, sem sistema integrado)", "total": 12, "noPeriodo": 12}]
    incidentes = [{"consultaId": f"c0000000-0000-4000-8000-00000000000{i}", "tipo": ("FALHA_NO_WORKER" if i % 2 else "SEM_AUDIO"),
                   "desde": (agora - timedelta(hours=2 * i + 1)).isoformat(), "erro": ("RuntimeError" if i % 2 else None)} for i in range(1, 8)]
    painel = {"geradoEm": agora.isoformat(), "periodoDias": 30,
              "agendamentos": {"porStatus": {"AGENDADO": 118, "CANCELADO": 9}, "criadosNoPeriodo": 127, "porSistema": sistemas_painel},
              "consultas": {"porStatus": {"CRIADA": 9, "AGUARDANDO_PACIENTE": 1, "EM_ANDAMENTO": 2, "ENCERRADA": 115}, "encerradasNoPeriodo": 115},
              "transcricao": {"jobsPorStatus": {"PENDENTE": 2, "ENFILEIRADO": 1, "CONCLUIDO": 109, "SEM_AUDIO": 2, "FALHA_NO_WORKER": 1}, "transcricoesGravadas": 109,
                              "audioAindaNoDisco": 4, "audioMaisAntigoDesde": (agora - timedelta(hours=3)).isoformat()},
              "webhooks": {"ENTREGUE": 106, "PENDENTE": 1, "FALHA": 2},
              "incidentes": incidentes}
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
        base.entrega(1, "ENTREGUE", "transcricao.concluida", 1, 200, None, 25),
        base.entrega(2, "PENDENTE", "transcricao.concluida", 2, 503, "HTTP 503", 12),
        base.entrega(3, "FALHA", "transcricao.indisponivel", 7, 400, "HTTP 400", 600)], metodo="GET")

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
    await asyncio.sleep(2)  # gráficos
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
    await adm.simular("/api/admin/medicos", ricardo, exato=True, metodo="POST")  # o cadastro não cria nada no banco
    await adm.clicar("Cadastrar")
    await adm.esperar_texto("Ricardo Nunes")
    await adm.foto("a05-medicos-lista", completo=True)
    await clicar_aria(adm, "Redefinir senha")
    await adm.esperar_texto("Nova senha de")
    await adm.foto("a06-medicos-redefinir-senha", completo=True)
    await adm.clicar("Cancelar")

    # Sistemas: lista com busca/paginação + modal com key no topo e webhook
    nome_real = "Hospital Modelo (integração)"
    integradores.append({"id": dados["integrador_id"], "nome": nome_real, "ativo": True, "webhookBaseUrl": None, "webhookAtivo": False,
                         "webhookTemSegredo": False, "criadoEm": agora.isoformat()})
    await adm.clicar("Sistemas e keys", "nav button")
    await adm.esperar_texto("Sistemas integrados")
    await adm.esperar_texto(nome_real)
    await adm.foto("a07-sistemas-lista", completo=True)
    await adm.js(f"""(()=>{{const l=[...document.querySelectorAll('tbody tr')].find(t=>t.textContent.includes({json.dumps(nome_real)}));
        l.querySelector('[aria-label=Abrir]').click();}})()""")
    await adm.esperar_texto("API keys")
    await adm.digitar("input[name=nomeKey]", "produção")
    await adm.foto("a08-sistema-modal", completo=True)
    await adm.clicar("Gerar key")
    await adm.esperar_texto("não será mostrado de novo")
    # A key de verdade é de desenvolvimento, mas não vai para o repositório: a captura mostra um valor de exemplo.
    await adm.js("""(()=>{const c=document.querySelector('.destaque-segredo input');
        c.value='mlk_0123456789abcdef0123456789abcdef_EXEMPLO-NAO-USE-ESTE-VALOR';})()""")
    await adm.foto("a08b-sistemas-key-criada", completo=True)
    await adm.clicar("Já guardei")
    await adm.digitar("#base", "https://api.cliente.com.br/hooks/medilink")
    await adm.foto("a09-sistemas-webhook", completo=True)
    await adm.clicar("Fechar")

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

    await adm.clicar("Envio de e-mail", "nav button")
    await adm.esperar_texto("Envio de e-mail (SMTP)")
    await adm.digitar("#host", "smtp.clinica.exemplo")
    await adm.digitar("#usuario", "no-reply@clinica.exemplo")
    await adm.digitar("#remetente", "no-reply@clinica.exemplo")
    await adm.foto("a13-smtp", completo=True)
    await adm.fechar()


async def main():
    dados = preparar_dados()
    st, sessao = api("POST", "/api/medico/login", {"email": dados["medico_email"], "senha": SENHA_MEDICO})
    token_medico = exigir((st, sessao))["token"]
    agora = datetime.now(timezone.utc)
    principal = agendar(token_medico, "Maria Souza", CPF, agora + timedelta(minutes=5), "maria.souza@exemplo.test")
    agendar(token_medico, "José Almeida", "111.444.777-35", agora + timedelta(days=1, hours=2))
    agendar(token_medico, "Fernanda Costa", "390.533.447-05", agora + timedelta(days=2, hours=1))

    perfil = tempfile.mkdtemp(prefix="medilink-manual-")
    chrome = iniciar_chrome(perfil)
    try:
        await paciente(principal["roomName"])
        await medico(dados, principal)
        await administrador(dados)
        print("capturas concluídas em", SAIDA, flush=True)
    finally:
        chrome.terminate()
        shutil.rmtree(perfil, ignore_errors=True)


if __name__ == "__main__":
    asyncio.run(main())
