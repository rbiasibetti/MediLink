# Plano técnico — MediLink

O COMO. O comportamento está em `spec.md`; as tarefas em `tasks.md`.

## Arquitetura

```
Angular (IFrame External API) ──► Spring Boot (agenda, CPF, JWT, aceite, transcrição)
        │                                   │
        ▼                                   ▼
 Jitsi Meet ─ Prosody ─ Jicofo        SQL Server ◄── worker faster-whisper ◄── Kafka/Redpanda
        │                                                    ▲
       JVB(s) ── WebSocket Opus ──► Audio sink ── arquivo transitório ──┘ (apagado após gravar Transcricao)
```

- **Captura:** Opção A (bridge → audio sink próprio). Jibri em modo áudio é o plano B (risco: maturidade da arquitetura de 2026).
- **Transcrição:** `faster-whisper`, modelo `turbo`, CPU `int8`, `language=pt`, `vad_filter=True`; áudio por locutor quando possível, senão diarização.
- **Mensageria:** Kafka em produção, Redpanda em dev/MVP (mesma API).
- **Escala:** JVBs horizontais (5–10+), sink stateless, workers por vazão, Prosody/Jicofo com tuning. Valores a confirmar em T15.

## Stack e decisões (backend)

| Decisão | Escolha | Motivo |
| --- | --- | --- |
| Framework | Spring Boot 4.1.2, Java 25, Maven (wrapper) | Stack definida no plano. |
| Banco | **SQL Server 2019+** em todos os ambientes (`mssql-jdbc`, Flyway `flyway-sqlserver`); bancos `medilink_dev` e `medilink_test` no SQL Server local | Decisão do projeto: **sem H2**. A migração usa tipos nativos (`UNIQUEIDENTIFIER`, `NVARCHAR`, `DATETIMEOFFSET`, `BIT`, `NVARCHAR(MAX)`) e todos os testes rodam contra o SQL Server real (`backend/run-mssql.sh test`). Validado em SQL Server 2022 Express; ainda não em 2019 exato. Em produção `ddl-auto=none` (o Flyway é dono do schema). |
| Ids | UUID gerado pela aplicação | Sem enumeração de recursos. |
| Link/sala | `roomName = "consulta-" + 128 bits aleatórios em hex`; link = `{public-base-url}/sala/{roomName}` | Link não adivinhável, único e persistente. |
| **CPF** | Guardado só como **HMAC-SHA256** (chave `medilink.cpf.hmac-key`), comparação em tempo constante | CPF tem 11 dígitos: hash simples (SHA-256) é reversível por força bruta; o HMAC com segredo do servidor evita isso. O sistema nunca precisa exibir o CPF de volta. |
| Validação de CPF | Dígitos verificadores na criação; resposta única e genérica na validação | Evita enumeração; limite de tentativas em T06. |
| Autenticação do médico | HTTP stateless; identidade = e-mail do `Medico`. Mecanismo de login definido em decisão aberta; em dev, usuário em memória. | O plano não especifica IdP do médico. |
| Auditoria | `AuditLog` gravado a cada criação/acesso a dado sensível | T14 amplia a cobertura. |

## Jitsi (T01/T02)

- Infra em `infra/jitsi/`: `docker-compose.yml` oficial (`jitsi/docker-jitsi-meet`) sem alterações, `.env.example` com as nossas escolhas e `dev.sh` (init/up/down/logs/env). Funciona com docker ou podman.
- **Autenticação por JWT** (`AUTH_TYPE=jwt`, `ENABLE_GUESTS=0`): sem token válido não se cria nem entra em sala. HS256 com `JWT_APP_SECRET` de 256 bits gerado por `dev.sh init`.
- `JitsiTokenService` (backend) assina o token com `iss`=`JWT_APP_ID`, `aud`=`JWT_ACCEPTED_AUDIENCES`, `sub`=`XMPP_DOMAIN`, `room` (um token por sala), `nbf`/`exp` (TTL 2 h) e `context.user`.
- **Papéis (T07):** o módulo `token_affiliation` precisa estar habilitado no componente `muc` (`XMPP_MUC_MODULES=token_affiliation` no `.env`); sem ele a claim `moderator` é ignorada e o médico entra como participante comum (verificado). Com ele, o médico vira `owner/moderator` e o paciente `member/participant`.
- **Aceite do médico (T08) — decisão:** a sala do Jitsi nasce aberta e o lobby só barra depois que um moderador o liga, então a porta do aceite fica no **backend**. O paciente que passou pelo CPF chama `POST /api/salas/{room}/token`: enquanto o médico não aceitar recebe **202** (`estado=AGUARDANDO_MEDICO`, sem JWT, `Retry-After: 3`) e repete a chamada; após `POST /api/consultas/{id}/aceite` recebe **200** com o JWT. Sem JWT ninguém entra no Jitsi (T02), então a sala aberta não é alcançável. O médico acompanha pelo `GET /api/consultas/{id}` (`pacienteAguardando`). Estados: `CRIADA` → `AGUARDANDO_PACIENTE` (chegada) → `EM_ANDAMENTO` (aceite, grava `inicioEm`). Não há aceite antecipado (409 se o paciente não chegou); o aceite é idempotente; consulta encerrada não dá mais credencial.
- **Desvio do plano original:** o plano previa o lobby do Jitsi para a espera. Usar o backend evita o aceite em duas etapas (backend + lobby) e a janela em que a sala está aberta. A sala de espera visível ao paciente é a tela do Angular em "aguardando o médico". O lobby do Jitsi continua habilitado na infra e pode ser ligado pelo cliente do médico (`toggleLobby`, T10) como defesa adicional, ao custo de um segundo clique do médico; **não foi verificado** neste projeto.
- **Jitsi não exposto (acesso só pelos links do MediLink):** camadas, da mais fraca para a mais forte.
  1. `ENABLE_WELCOME_PAGE=0`: sem página inicial.
  2. `infra/jitsi/nginx-custom/10-bloquear-acesso-direto.conf`: toda requisição com `Sec-Fetch-Dest: document` (aba ou URL digitada) recebe 403; o iframe do MediLink (`iframe`) e os recursos que ele carrega (script, BOSH, websocket) passam. O `dev.sh up` instala o arquivo em `${CONFIG}/web/nginx-custom/`, que o init do container copia e o `meet.conf` inclui.
  3. `CSP_HEADER="frame-ancestors 'self' <origem do MediLink>"`: outro site não consegue embutir a interface. Em produção, trocar `http://localhost:4200` pelo domínio real.
  4. **A fronteira de segurança continua sendo o JWT** (T02): sem token válido ninguém cria nem entra em sala, mesmo burlando as camadas acima. O bloqueio por `Sec-Fetch-Dest` só esconde a interface de quem usa um navegador; um cliente que não é navegador pode omitir ou forjar o cabeçalho, e navegadores muito antigos (sem `Sec-Fetch-*`) não são bloqueados.
  5. Portas: só web (8443, e 8000 que redireciona) e o UDP do JVB (10000) são publicados; Jicofo (8888) e a API do JVB (8080) ficam em `127.0.0.1`; o Prosody não é publicado.
- **Efeito colateral em dev:** abrir `https://localhost:8443/` direto agora mostra "Acesso direto bloqueado". Para aceitar o certificado de desenvolvimento isso basta (o aviso de certificado vem antes), e o vídeo carrega normalmente dentro do MediLink. O redirect da porta 8000 perde a porta (vai para `https://localhost/`), um limite do template do Jitsi; em produção, com a 443 padrão, funciona.
## Configuração da sala (restrições e preferências do Jitsi)

Onde mora cada coisa: `infra/jitsi/custom-config.js` (anexado ao `config.js` do Jitsi; o `dev.sh up` instala), `JitsiTokenService.FEATURES` (claims `context.features` do JWT) e `JitsiRoom` (comandos ao entrar). "Imposta" = o servidor recusa; "interface" = só some da tela (um participante que mexa no próprio navegador pode contornar).

| Pedido | Como foi feito | Força |
| --- | --- | --- |
| ~~Criptografia de ponta a ponta ativa em toda sala~~ | **Revisto em 2026-10-28: E2EE desligada por padrão.** Existe como flag no frontend (`E2EE_ATIVA_POR_PADRAO` em `config.ts`, e o input `e2ee` do `JitsiRoom`): ligada, o médico (moderador) chama `toggleE2EE(true)` ao entrar. Ver "Decisão: E2EE desligada" | Cliente. Não verificado com mídia real |
| Impossibilidade de desligar a criptografia | Vale se o flag for ligado: a barra não inclui `security` (único lugar com o interruptor da E2EE) e o cliente só envia `toggleE2EE(true)`. Com a E2EE desligada por padrão, não há o que desligar | Interface |
| Supressão de ruído | `setNoiseSuppressionEnabled({enabled:true})` ao entrar (RNNoise, que vem no build) + processamento de áudio do navegador explicitamente ligado (`disableAP/NS/AEC/AGC=false`); o botão `noisesuppression` fica na barra | Cliente |
| Desempenho nível 2 | Escala 0–3 da tela "Definições de desempenho": 0 melhor desempenho, 1 LD, **2 definição padrão (SD)**, 3 HD. `setVideoQuality(360)` ao entrar; o botão `videoquality` continua na barra, então o participante pode mudar | Cliente (preferência, não trava) |
| Convidar alguém / convidar pessoas / buscar participantes | `disableInviteFunctions=true`; sem `invite` nem `participants-pane` na barra; `speakerStats` desligado (tem campo de busca) | Interface |
| Adicionar sala de apoio | `breakoutRooms.hide*=true`, `ENABLE_BREAKOUT_ROOMS=0`; sem `participants-pane` | Interface |
| Compartilhamento de tela | Sem `desktop`/`shareaudio` na barra; `features["screen-sharing"]=false` no JWT; atalhos desligados | Cliente (token assinado) |
| Chat | `disableChat=true`; sem `chat` na barra; `features["send-groupchat"]=false` | Cliente (token assinado). O filtro do Prosody só vale com a sala marcada como "chat restrito" |
| Erguer/abaixar a mão | Sem `raisehand` na barra, `disableShortcuts=true` e `disableReactions=true` (o menu de reações também oferece "levantar a mão") | Interface |
| Compartilhamento de vídeo | Sem `sharedvideo` na barra | Interface |
| Enquetes | `disablePolls=true` + `DISABLE_POLLS=1` + `features["create-polls"]=false` | **Imposta** (`mod_polls_component`) |
| Gravação, transmissão, ligações, envio de arquivos (não pedidos, desligados por segurança) | `features` = false no JWT | **Imposta** (filtros de IQ do Prosody) |
| Transcrição (captura no servidor, T11) | `features.transcription=true` **só no JWT do médico**; o sink é o portão do consentimento | Imposta (Prosody + sink) |

A barra de ferramentas é: microfone, câmera, supressão de ruído, definições de desempenho, grade, tela cheia, configurações (escolha de dispositivos) e desligar. Isto é uma escolha mínima minha: o que não foi pedido (efeitos de fundo, estatísticas, legendas) ficou de fora e se recoloca em `toolbarButtons`.

### Decisão: E2EE desligada (captura de áudio no servidor)

**Data:** 2026-10-28. **Status:** adotada, pendente de validação pelo DPO/jurídico.

**Contexto.** O plano captura o áudio da consulta no servidor (bridge → audio sink → fila → Whisper, T11 a T13) e descarta o arquivo após gravar a transcrição. Foi pedida criptografia de ponta a ponta (E2EE) em toda sala. As duas coisas são incompatíveis: com E2EE o JVB só vê quadros cifrados e não entrega áudio utilizável ao sink. Além disso, o Jitsi usa P2P em sala de 2 pessoas, e a mídia nem passa pelo JVB.

**Opções avaliadas.**
1. *Captura no navegador*, com upload do áudio de cada participante ao backend. Combina com E2EE e dá uma faixa por locutor, mas foi **rejeitada: o upload não pode ficar sob responsabilidade do cliente** (falha de rede, aba fechada ou cliente adulterado perdem ou alteram o conteúdo da consulta).
2. *Chave da E2EE gerenciada pelo MediLink* (`e2ee.externallyManagedKey`): mantém a captura no servidor, mas o servidor passa a ter a chave (deixa de ser ponta a ponta de verdade) e exige implementar o formato de quadro do Jitsi. Descartada pelo custo.
3. **Sem E2EE, captura no servidor (escolhida).**

**Raciocínio.**
- A LGPD (art. 46) exige medidas de segurança técnicas e administrativas "aptas a proteger" os dados, sem prescrever técnica; o §1º deixa a ANPD fixar padrões mínimos. Não encontrei exigência de E2EE na LGPD nem, pelas fontes secundárias lidas, na Resolução CFM 2.314/2022, que trata de confidencialidade, sigilo profissional, termo de consentimento e (para o registro eletrônico) NGS2. **Não consegui ler o texto oficial do CFM** (PDF protegido), então "não exige E2EE" está **não confirmado na fonte**.
- Sem E2EE a mídia continua cifrada em trânsito: DTLS-SRTP do navegador ao JVB e sinalização por TLS. O JVB, que é nosso, decifra o áudio, e é isso que permite transcrever; o tratamento ocorre na infraestrutura da própria clínica, para a finalidade consentida e sem terceiros.
- O ganho da E2EE contra um invasor do servidor é pequeno aqui, porque a transcrição fica gravada no banco de qualquer forma. O risco real está no banco, nos arquivos temporários de áudio, nos backups e em quem tem acesso. É onde as condições abaixo atuam.

**Consequências.**
- `E2EE_ATIVA_POR_PADRAO = false` (frontend). `ENABLE_P2P=0` no `.env` e `config.p2p.enabled=false` no `custom-config.js`: a mídia sempre passa pelo JVB, mesmo em sala de 2 pessoas. A barra de ferramentas continua sem `security`.
- A T11 fica liberada (captura via bridge). Para reverter, ligar o flag e aceitar que a captura no servidor deixa de funcionar.
- O P2P desligado aumenta a carga do JVB (toda mídia passa por ele); o teste de carga (T15) já considera isso (2.000 streams para 1.000 salas).

### Audio sink (T11): como a captura no servidor funciona

**Fluxo.** O médico (cliente) pede a transcrição → o Jicofo marca a reunião → o JVB abre **um** WebSocket para o sink e envia o Opus de cada participante (protocolo `mediajson`: `info`, `start`, `media`, `stop`, `ping`/`pong`) → o sink grava um arquivo Ogg Opus por fonte.

**Peças (todas verificadas ponta a ponta com Jitsi real, ver abaixo):**
- **Sink** (`backend/.../sink`, Spring WebSocket): `GET /api/audio-sink/{meetingId}?sala={roomName}`. Handshake exige `Authorization: Bearer {SINK_TOKEN}` (comparação em tempo constante); sem o token certo, 401. Responde `pong` a cada `ping` (o JVB derruba a conexão se não houver resposta).
- **Portão do consentimento no próprio sink:** a cada quadro (cache de 1 s) consulta `Consulta.capturaAutorizada()` (consulta em andamento **e** consentimento vigente). Sem isso, descarta o quadro e não cria arquivo, **não importa quem pediu a exportação**. Retirar o consentimento fecha os arquivos em até 1 s.
- **Gravação:** `OggOpusWriter` (RFC 7845, CRC do Ogg; ffmpeg e faster-whisper leem direto) em `${medilink.sink.dir}/{consultaId}/{tag}-{epochMs}.opus`, permissões `600` (pasta `700`). Lacunas na linha do tempo (silêncio/DTX, perda) viram quadros de silêncio (até 10 min), para os arquivos das fontes ficarem alinhados; duplicados e fora de ordem são ignorados. O banco guarda só o registro (`audio_transitorio`: consulta, fonte, `endpointId`, arquivo, status, amostras); o áudio nunca vai para o banco.
- **Módulo Prosody `mod_medilink_transcription`** (`infra/jitsi/prosody-plugins`): marca toda sala com `asyncTranscription=true` e `transcription.urlParams.sala=<roomName>`. Sem ele o Jicofo nunca liga a exportação (a versão aberta não tem quem defina esse metadado) e o sink não saberia a qual consulta o áudio pertence (o JVB só manda o `meeting_id`).
- **Jicofo:** `custom-jicofo.conf` (gerado do template pelo `dev.sh up`) com `jicofo.transcription.url-template` e o cabeçalho `Authorization`. A URL é a do backend visto de dentro do container (`host.containers.internal` no podman).
- **Gatilho:** o `JitsiRoom` do médico chama `setSubtitles(true,false)` enquanto `capturaAutorizada` for verdadeiro e `setSubtitles(false,false)` quando deixar de ser. Só o JWT do médico tem `features.transcription=true`.

**O que ficou de fora / limites conhecidos.**
- O **gatilho é do cliente do médico**. Se a aba dele cair antes de pedir, não há captura (e a perda é silenciosa). O sink já garante que nada é gravado sem consentimento; o que falta é garantir que algo **seja** gravado. Endurecimento recomendado: o próprio Prosody (ou o backend, via módulo) ligar `recording.isTranscribingEnabled` a partir do estado do consentimento, sem depender do navegador.
- **Quem é quem:** os arquivos vêm por `tag` (`{endpointId}-a0`); o `endpointId` fica no banco, mas ainda não se sabe qual é o médico e qual é o paciente. A T12 precisa mapear (o cliente conhece o próprio `endpointId` no evento `videoConferenceJoined`).
- **Tempo:** os timestamps vêm do JVB, não do relógio de parede; o início de cada arquivo é o instante em que abriu (no nome) e vale como âncora para alinhar as fontes na T12.
- **Produção:** `ws://` só em dev. Em produção o sink precisa de `wss://` (TLS até o backend), `SINK_TOKEN` forte em cofre, a pasta de áudio em disco cifrado (T14) e limite de tamanho por arquivo. Um sink por instância do backend; com várias, o `url-template` deve ir para um balanceador (T15).
- Um dia de captura é pequeno (~4 kB/s por fonte), mas **nada apaga o áudio ainda**: o descarte é a T13.

**Achado de passagem (corrigido):** a opção `prejoinPageEnabled` não existe mais no Jitsi desta versão e é ignorada em silêncio; o participante ficava parado na tela de pré-entrada. A opção atual é `prejoinConfig.enabled`; corrigida no `JitsiRoom` e no `custom-config.js`.

**Como verificar:** `infra/jitsi/e2e/verify_t11.py` (precisa do Jitsi no ar, do backend em dev, da porta 4200 livre e do Chrome). Cenário A: consente, aceita, os dois entram e o médico pede a transcrição. Cenário B: sem consentimento. Confere estrutura e CRC dos Ogg, que nada foi gravado antes do aceite e que a duração do arquivo bate com o tempo real.

**Condições para a decisão se sustentar** (estado atual):

| Condição | Estado |
| --- | --- |
| Certificado válido e HTTPS forçado em produção (hoje: autoassinado em dev) | Pendente (produção) |
| Tráfego interno cifrado: JVB → sink, fila, worker, SQL Server (`encrypt=true` e `trustServerCertificate=false`; hoje o dev usa `true`) | Pendente (T11 a T14) |
| Criptografia em repouso: áudio temporário, transcrição, backups (TDE ou criptografia de coluna) | Pendente (T14) |
| Acesso por papel e auditoria de leitura de áudio, CPF e transcrição; descarte do áudio após gravar a transcrição | Parcial: auditoria básica pronta; descarte na T13; leitura na T14 |
| Base legal, Relatório de Impacto, registro das operações e plano de incidente documentados | Pendente (DPO/jurídico) |
| Termo de consentimento fiel ao tratamento real (já diz: só áudio, transcrição, descarte do áudio, sem terceiros) | Minuta pronta; validar com jurídico |

- Logs: o Prosody registra o JWT inteiro em erros de `token_verification`; tratar como sensível.
- Em produção, avaliar assinatura assimétrica (RS256 + `JWT_ASAP_KEYSERVER`) para o segredo não precisar estar no Prosody.

## Acesso do paciente e e-mail (T05/T06)

- **E-mail (T05):** `POST /api/agendamentos/{id}/enviar-email`, só para o médico dono. Texto simples em UTF-8; não leva CPF nem dado do paciente além do nome. Sucesso e falha vão para o `AuditLog` (`EMAIL_LINK_ENVIADO`/`EMAIL_LINK_FALHA`); falha do relay vira 502 sem repassar a mensagem do SMTP (poderia citar o destinatário). O indicador de saúde de e-mail do actuator está desligado: queda do relay não derruba a readiness. Em dev, `infra/dev/smtp_sink.py` faz o papel de relay.
- **Tela de acesso:** `GET /api/salas/{roomName}/acesso` devolve só médico, data/hora e `cpfObrigatorio`. Nome do paciente e CPF nunca saem.
- **Validação (T06):** `POST /api/salas/{roomName}/validar-cpf`. CPF errado, sala inexistente e agendamento cancelado dão **a mesma resposta 403 genérica**. Com a validação desativada pelo médico o corpo é opcional e o token sai sem CPF.
- **Limite de tentativas:** 5 falhas em 15 min por agendamento → 429 com `Retry-After`, inclusive para o CPF correto (tabela `tentativa_cpf`, só o instante da falha; vale com várias instâncias). Custo conhecido: quem tem o link pode atrasar o paciente por até 15 min. Rotina de limpeza da tabela fica para a T14 (retenção).
- **Token de entrada:** JWT HS256 de 10 min, amarrado à sala, com audiência e chave derivada próprias (não serve no Jitsi). O T07 o troca pelo JWT do Jitsi do paciente.

## Consentimento (T09)

- **Quem e como:** o paciente consente com o token de entrada (emitido após o CPF) da sala da própria consulta: `POST /api/consultas/{id}/consentimento` com `{tokenEntrada, aceito, versaoTermo}`. Rota pública no `SecurityConfig`; consulta inexistente, token inválido ou de outra sala dão a mesma 403. O médico não consente em nome do paciente.
- **Termo versionado:** `GET /api/termo-consentimento` devolve `{versao, texto}` (arquivo `termo-consentimento-v1.txt`, **minuta de engenharia a validar com jurídico/DPO**). O paciente envia a versão que leu; diferente da vigente → 409. O banco guarda `consentimento_em` e `consentimento_versao` (migração V3): prova de qual texto foi aceito.
- **Recusa e retirada:** `aceito=false` recusa (sem consentimento) ou retira um já dado (limpa `consentimento_em`); a consulta segue normalmente, sem captura. Ambos auditados (`CONSENTIMENTO_RECUSADO`/`_REVOGADO`).
- **Portão da captura:** `Consulta.capturaAutorizada()` = `EM_ANDAMENTO` **e** consentimento vigente; aparece para o médico em `GET /api/consultas/{id}` (`capturaAutorizada`). O audio sink (T11) deve consultá-lo e interromper a captura se virar falso.
- **Limitação conhecida:** o token de entrada dura 10 min. Se o paciente esperar o aceite por mais que isso, as chamadas dele passam a dar 403 e ele repete o CPF. Consentir logo após o CPF evita o problema; renovar o token durante a espera é melhoria pendente.

## Frontend Angular (T10)

- **Stack:** Angular 21 (standalone, zoneless, signals), Vitest. A linha 21 foi escolhida porque o Node do ambiente é o 20.20; o CLI 22 exige Node 22+. O npm 10.8.2 quebra a resolução de peer deps deste projeto (`edgesOut`); o `frontend/.npmrc` fixa `legacy-peer-deps=true`.
- **Rotas:** `/sala/:roomName` (link do paciente: CPF → termo → espera → sala), `/medico` (login provisório), `/medico/agendar`, `/medico/consulta/:agendamentoId` (sala do host). O `proxy.conf.json` leva `/api` ao backend (`:8090`), sem CORS em dev.
- **Regras na interface:** o paciente só entra na sala admitido pelo médico **e** depois de responder ao termo (aceitando ou recusando); pode retirar o consentimento na sala. Credenciais do médico só em memória (recarregar pede login). O CPF vai só no corpo de um POST e é limpo do estado depois de usado.
- **Encerramento:** o `JitsiRoom` embute a IFrame API e emite `saiu` uma vez em `videoConferenceLeft`/`readyToClose`. A página do médico, ao receber, lê o estado atual e **só encerra se a consulta estava `EM_ANDAMENTO`**; sair antes de admitir ninguém não encerra. Paciente sair não encerra (só o médico: `POST /api/consultas/{id}/encerrar`, idempotente).
- **Limitações conhecidas:** (1) fechar a aba sem sair da conferência não dispara o evento, então a consulta fica em andamento; precisa de um encerramento por inatividade no servidor (a combinar com a T11). (2) O Jitsi precisa de HTTPS com certificado confiável no navegador: a IFrame API fixa `https://`, então Jitsi em HTTP não serve para dev. (3) O login do médico é o Basic provisório.

## Contratos de API

| Método | Rota | Função | Tarefa |
| --- | --- | --- | --- |
| POST | `/api/agendamentos` | Cria agendamento; retorna o link | T04 |
| POST | `/api/agendamentos/{id}/enviar-email` | Envia o link ao paciente | T05 |
| GET | `/api/salas/{roomName}/acesso` | Dados mínimos para a tela de acesso | T06 |
| POST | `/api/salas/{roomName}/validar-cpf` | Valida CPF; retorna token de entrada | T06/T07 |
| POST | `/api/agendamentos/{id}/token-jitsi` | JWT do médico (host/moderador) para a sala | T07 |
| POST | `/api/salas/{roomName}/token` | Troca o token de entrada pelo JWT do paciente | T07 |
| GET | `/api/termo-consentimento` | Termo vigente (versão e texto), público | T09 |
| POST | `/api/consultas/{id}/consentimento` | Registra, recusa ou retira o consentimento (paciente) | T09 |
| POST | `/api/consultas/{id}/aceite` | Host admite o paciente | T08 |
| POST | `/api/consultas/{id}/encerrar` | Médico encerra a consulta em andamento (idempotente); a fila da transcrição entra na T12 | T10 |
| GET | `/api/me` | Nome e e-mail do médico autenticado (valida o login) | T10 |
| GET | `/api/consultas/{id}/transcricao` | Retorna a transcrição quando pronta | T13 |

**JWT do Jitsi:** médico com `moderator=true`; paciente sem moderação, emitido só após o CPF (quando ativo). Claims amarram `agendamentoId`, `medicoId` e o paciente. Sala de espera = lobby do Jitsi.

## Idempotência do descarte (T13)

1. Gravar `Transcricao` (unique por `consultaId`) numa transação.
2. Só após o commit, apagar o áudio; a remoção é idempotente (arquivo ausente = sucesso).
3. Falha em qualquer ponto antes do commit mantém o áudio e permite reprocessar; falha depois do commit apenas repete o passo 2.
4. O job só termina com sucesso com `Transcricao` persistida e áudio removido.

## Decisões em aberto

Relay de e-mail em produção (SMTP próprio × serviço); SLA da transcrição (minutos × horas) e nº de GPUs; pico de 1.000 sustentado ou raro; modelo Whisper e terminologia médica; relay de e-mail; política de retenção; **IdP/login do médico**.
