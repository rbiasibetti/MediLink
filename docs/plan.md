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
| Auditoria | `AuditLog` gravado a cada criação/acesso a dado sensível | T14: leitura da transcrição e acessos negados auditados; trilha só de inserção. |

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
- **Gravação:** `OggOpusWriter` (RFC 7845, CRC do Ogg; ffmpeg e faster-whisper leem direto) em `${medilink.sink.dir}/{consultaId}/{tag}-{epochMs}.opus.enc` (**cifrado desde a T14**, formato MLA1 abaixo), permissões `600` (pasta `700`). Lacunas na linha do tempo (silêncio/DTX, perda) viram quadros de silêncio (até 10 min), para os arquivos das fontes ficarem alinhados; duplicados e fora de ordem são ignorados. O banco guarda só o registro (`audio_transitorio`: consulta, fonte, `endpointId`, arquivo, status, amostras); o áudio nunca vai para o banco.
- **Módulo Prosody `mod_medilink_transcription`** (`infra/jitsi/prosody-plugins`): marca toda sala com `asyncTranscription=true` e `transcription.urlParams.sala=<roomName>`. Sem ele o Jicofo nunca liga a exportação (a versão aberta não tem quem defina esse metadado) e o sink não saberia a qual consulta o áudio pertence (o JVB só manda o `meeting_id`).
- **Jicofo:** `custom-jicofo.conf` (gerado do template pelo `dev.sh up`) com `jicofo.transcription.url-template` e o cabeçalho `Authorization`. A URL é a do backend visto de dentro do container (`host.containers.internal` no podman).
- **Gatilho:** o `JitsiRoom` do médico chama `setSubtitles(true,false)` enquanto `capturaAutorizada` for verdadeiro e `setSubtitles(false,false)` quando deixar de ser. Só o JWT do médico tem `features.transcription=true`.

**O que ficou de fora / limites conhecidos.**
- O **gatilho é do cliente do médico**. Se a aba dele cair antes de pedir, não há captura (e a perda é silenciosa). O sink já garante que nada é gravado sem consentimento; o que falta é garantir que algo **seja** gravado. Endurecimento recomendado: o próprio Prosody (ou o backend, via módulo) ligar `recording.isTranscribingEnabled` a partir do estado do consentimento, sem depender do navegador.
- **Quem é quem:** os arquivos vêm por `tag` (`{endpointId}-a0`); o `endpointId` fica no banco, mas ainda não se sabe qual é o médico e qual é o paciente. A T12 precisa mapear (o cliente conhece o próprio `endpointId` no evento `videoConferenceJoined`).
- **Tempo:** os timestamps vêm do JVB, não do relógio de parede; o início de cada arquivo é o instante em que abriu (no nome) e vale como âncora para alinhar as fontes na T12.
- **Produção:** `ws://` só em dev. Em produção o sink precisa de `wss://` (TLS até o backend), `SINK_TOKEN` forte em cofre, limite de tamanho por arquivo (o arquivo já é cifrado pela aplicação, T14; disco cifrado é camada extra). Um sink por instância do backend; com várias, o `url-template` deve ir para um balanceador (T15).
- Um dia de captura é pequeno (~4 kB/s por fonte). O áudio é apagado depois de a transcrição ser gravada (T13, abaixo).

**Achado de passagem (corrigido):** a opção `prejoinPageEnabled` não existe mais no Jitsi desta versão e é ignorada em silêncio; o participante ficava parado na tela de pré-entrada. A opção atual é `prejoinConfig.enabled`; corrigida no `JitsiRoom` e no `custom-config.js`.

**Como verificar:** `infra/jitsi/e2e/verify_t11.py` (precisa do Jitsi no ar, do backend em dev, da porta 4200 livre e do Chrome). Cenário A: consente, aceita, os dois entram e o médico pede a transcrição. Cenário B: sem consentimento. Confere estrutura e CRC dos Ogg, que nada foi gravado antes do aceite e que a duração do arquivo bate com o tempo real.

**Condições para a decisão se sustentar** (estado atual):

| Condição | Estado |
| --- | --- |
| Certificado válido e HTTPS forçado em produção (hoje: autoassinado em dev) | Pendente (produção) |
| Tráfego interno cifrado: JVB → sink, fila, worker, SQL Server (`encrypt=true` e `trustServerCertificate=false`; hoje o dev usa `true`) | **Imposto na subida em `prod`** (T14: `ExigenciasDeProducao` recusa iniciar sem TLS verificado no banco, HTTPS/WSS e fila em SSL); **configurado, ainda não exercitado** com certificados de verdade (dev segue em texto puro) |
| Criptografia em repouso: áudio temporário, transcrição, backups (TDE ou criptografia de coluna) | **Áudio e transcrição: feito na aplicação (T14, AES-256-GCM, verificado)**. Backups e disco: da infraestrutura (TDE exige SQL Server Standard/Enterprise; a Express do desenvolvimento não tem) |
| Acesso por papel e auditoria de leitura de áudio, CPF e transcrição; descarte do áudio após gravar a transcrição | **Feito (T13/T14)**: leitura da transcrição e tentativas negadas auditadas; trilha só de inserção no banco; o CPF nunca é lido (só HMAC) e o áudio só é lido pelo worker. Falta auditar o login do médico (provisório) |
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
- **Termo versionado:** `GET /api/termo-consentimento` devolve `{versao, texto}` (arquivo `termo-consentimento-v2.txt`; o `v1` fica no repositório só como histórico; **minuta de engenharia a validar com jurídico/DPO**; a evolução do termo pelo painel administrativo está na proposta T18). O paciente envia a versão que leu; diferente da vigente → 409. O banco guarda `consentimento_em` e `consentimento_versao` (migração V3): prova de qual texto foi aceito.
- **Consentimento obrigatório (regra de 2026-10-30).** Atendimento médico precisa sempre ser registrado, então **sem aceitar o termo o paciente não segue**: `POST /api/salas/{room}/token` responde `409` (antes de registrar a chegada), o aceite do médico também responde `409` e o paciente nem chega à sala de espera. A ordem do paciente passou a ser CPF → termo → espera → sala. Para isso `POST /validar-cpf` devolve também o `consultaId` (o paciente precisa dele para responder ao termo antes de qualquer espera). Recusar continua sendo registrado e auditado (`CONSENTIMENTO_RECUSADO`), mas agora bloqueia; o paciente pode reler e aceitar depois.
- **Retirada durante a consulta:** `aceito=false` limpa o consentimento (`CONSENTIMENTO_REVOGADO`). A captura para em até 1 s (o sink confere), o paciente **não recebe nova credencial do Jitsi** (`409`) e a tela dele descarta o iframe e encerra a participação; a tela do médico mostra o alerta "esta consulta não está sendo registrada" para ele encerrar. **Limite:** o servidor ainda não expulsa o paciente do Jitsi; um cliente adulterado poderia permanecer na sala sem registro até o médico encerrar. A correção é expulsão pelo Prosody ao detectar a retirada (junto do gatilho de captura no servidor, hoje pendente) e deve entrar nas próximas etapas. Enquanto isso, **uma consulta que termina sem áudio passa a ser incidente** (`SEM_AUDIO`), a ser sinalizado no painel (T18).
- **Impacto jurídico (a validar):** o consentimento deixou de ser opcional; a base legal do tratamento (consentimento livre × tutela da saúde/obrigação legal/regulatória, LGPD art. 11) e o texto do termo precisam ser revistos pelo jurídico/DPO. O termo v2 reescreve só o item 6 e a frase final; o item 4 ("sem envio a terceiros") deixará de valer se o webhook (T19) for usado.
- **Portão da captura:** `Consulta.capturaAutorizada()` = `EM_ANDAMENTO` **e** consentimento vigente; aparece para o médico em `GET /api/consultas/{id}` (`capturaAutorizada`). O audio sink (T11) deve consultá-lo e interromper a captura se virar falso.
- **Limitação conhecida:** o token de entrada dura 10 min. Se o paciente esperar o aceite por mais que isso, as chamadas dele passam a dar 403 e ele repete o CPF. Consentir logo após o CPF evita o problema; renovar o token durante a espera é melhoria pendente.

## Frontend Angular (T10)

- **Stack:** Angular 21 (standalone, zoneless, signals), Vitest. A linha 21 foi escolhida porque o Node do ambiente é o 20.20; o CLI 22 exige Node 22+. O npm 10.8.2 quebra a resolução de peer deps deste projeto (`edgesOut`); o `frontend/.npmrc` fixa `legacy-peer-deps=true`.
- **Rotas:** `/sala/:roomName` (link do paciente: CPF → termo → espera → sala), `/medico` (login provisório), `/medico/agendar`, `/medico/consulta/:agendamentoId` (sala do host). O `proxy.conf.json` leva `/api` ao backend (`:8090`), sem CORS em dev.
- **Regras na interface:** o paciente só chega à espera depois de **aceitar** o termo e só entra na sala admitido pelo médico; recusar mostra "atendimento não realizado" (com a opção de reler o termo) e retirar o consentimento na sala encerra a participação. Credenciais do médico só em memória (recarregar pede login). O CPF vai só no corpo de um POST e é limpo do estado depois de usado.
- **Encerramento:** o `JitsiRoom` embute a IFrame API e emite `saiu` uma vez em `videoConferenceLeft`/`readyToClose`. A página do médico, ao receber, lê o estado atual e **só encerra se a consulta estava `EM_ANDAMENTO`**; sair antes de admitir ninguém não encerra. Paciente sair não encerra (só o médico: `POST /api/consultas/{id}/encerrar`, idempotente).
- **Limitações conhecidas:** (1) fechar a aba sem sair da conferência não dispara o evento, então a consulta fica em andamento; precisa de um encerramento por inatividade no servidor (a combinar com a T11). (2) O Jitsi precisa de HTTPS com certificado confiável no navegador: a IFrame API fixa `https://`, então Jitsi em HTTP não serve para dev. (3) O login do médico é o Basic provisório.

## Transcrição pós-consulta (T12)

**Fluxo.** O médico sai da sala → `POST /api/consultas/{id}/encerrar` cria o `transcricao_job` (um por consulta) → uma rotina do backend (a cada 5 s) espera os arquivos de áudio fecharem e publica o job no tópico `medilink.transcricao.jobs` (chave = consulta) → o worker transcreve cada fonte e publica o texto em `medilink.transcricao.resultados`. A T13 consome o resultado, grava a `Transcricao` e descarta o áudio.

**Quem é quem.** O JVB só identifica a fonte pelo endpoint do Jitsi. Ao entrar na sala, cada cliente informa o próprio endpoint em `POST /api/consultas/{id}/participante`; o **papel vem da autenticação** (médico dono = `MEDICO`; paciente com o token de entrada = `PACIENTE`), nunca do que o cliente diz. Um endpoint não troca de papel (409) e a consulta encerrada não aceita registro. Quem não se registrou vira `DESCONHECIDO` no job. O mapa é informado pelo cliente (não é fonte autoritativa do servidor); o pior caso de adulteração é trocar rótulos dentro da própria consulta.

**Estados do job** (`transcricao_job.status`): `PENDENTE` (criado ao encerrar) → `ENFILEIRADO`; ou `SEM_AUDIO` (a consulta terminou sem captura: hoje só por falha técnica, porque sem consentimento o paciente nem entra; nada a transcrever e nada publicado, e **é um incidente a investigar**); ou `FALHA_NO_ENVIO` (20 tentativas com o broker fora do ar; o áudio é mantido). Um arquivo ainda em `GRAVANDO` é aguardado por até 10 min depois do fim da consulta, depois o job segue assim mesmo. Só o **tipo** da exceção é guardado (`ultimo_erro`), nunca a mensagem. A entrega é "pelo menos uma vez": se o envio der certo e o commit falhar, o job é reenviado e o worker deduplica pelo `jobId`.

**Contrato do job** (`medilink.transcricao.jobs`, só referências; sem áudio, texto, CPF ou nome):

```json
{"versao":1,"jobId":"…","consultaId":"…","idioma":"pt","encerradaEm":"2026-10-29T12:30:00Z",
 "fontes":[{"audioId":"…","arquivo":"{consultaId}/{tag}-{epochMs}.opus.enc","endpointId":"7bc45eb8",
            "papel":"MEDICO|PACIENTE|DESCONHECIDO","iniciadoEm":"2026-10-29T12:00:03.100Z"}]}
```

**Contrato do resultado** (`medilink.transcricao.resultados`, chave = consulta; **contém o texto da consulta, dado sensível de saúde**):

```json
{"versao":1,"jobId":"…","consultaId":"…","status":"CONCLUIDO|FALHA","idioma":"pt","duracaoMs":32249,
 "segmentos":[{"papel":"MEDICO","inicioMs":3120,"fimMs":9800,"texto":"…"}],
 "fontes":[{"audioId":"…","papel":"MEDICO","duracaoMs":31600,"falas":3}],"erro":"TipoDoErro"}
```

`inicioMs`/`fimMs` são relativos ao primeiro quadro de áudio de qualquer fonte. Cada arquivo começa no instante em que o sink o abriu (`iniciadoEm`), e as lacunas já vêm preenchidas com silêncio (T11), então o tempo absoluto de uma fala é `iniciadoEm` do arquivo mais o tempo dela dentro dele. Falas sobrepostas de médico e paciente são mantidas.

**Worker** (`worker/`, Python 3.12): `faster-whisper`, modelo `turbo`, CPU `int8`, `language="pt"` fixo, VAD ligado, `condition_on_previous_text=False`, e descarte de trechos que o Whisper marca como sem fala (`no_speech_prob > 0.6` e `avg_logprob < -1.0`). O modelo é carregado uma vez e o `commit` da mensagem só acontece depois de o resultado ser entregue ao broker (`acks=all`, produtor idempotente). Mensagem fora do contrato é descartada sem travar a fila; falha inesperada vira resultado `FALHA` (só o tipo) e o áudio é mantido; falha em uma fonte não derruba as outras. O caminho do arquivo vem do job e é validado: sem caminho absoluto, `..` ou link simbólico que saia da pasta do sink. **O texto nunca vai para log.**

**Medição** (8 núcleos, CPU, `int8`): 22 s para 45 s de áudio em português (≈ 2× o tempo real) por fonte, idioma detectado `pt` com confiança 1,00. Uma consulta de 30 min com duas fontes custa da ordem de 30 min de CPU, menos com o VAD cortando silêncio. Isso alimenta o dimensionamento da T15 (GPU ou mais workers) e a decisão em aberto sobre o SLA da transcrição.

**Versões fixadas** (`worker/requirements.txt`): `faster-whisper==1.2.1`, `confluent-kafka==2.16.1` e **`av==16.1.0`**. O faster-whisper declara só `av>=11`, mas chama `av.open(metadata_errors=…)`, removido no PyAV 17+; sem o pin o `pip` instala o 19 e a transcrição quebra.

**Fora de escopo da T12 / limites.**
- Quem consome o resultado e grava a `Transcricao` é a **T13** (seção abaixo). O texto continua no tópico depois de gravado, até a retenção do broker (24 h por padrão desde a T14); cifragem e ACL do tópico são do broker (TLS configurado na T14).
- O worker roda no host (venv); falta empacotar em container e escalar (T15).
- O sink e o worker compartilham a pasta de áudio (`MEDILINK_SINK_DIR`); em produção isso exige volume compartilhado cifrado.
- O texto não passa por revisão: vocabulário médico (nomes de remédios, termos técnicos) pode ser transcrito errado; avaliar o modelo e um glossário é decisão em aberto.

## Gravação da transcrição e descarte do áudio (T13)

**Fluxo.** O backend lê o tópico `medilink.transcricao.resultados` (grupo `medilink-backend`, `earliest`, offset confirmado só depois de o listener retornar) → valida a mensagem → grava a `Transcricao` e marca o job `TRANSCRITO` numa transação → **depois do commit** apaga cada arquivo de áudio, marca a linha `DESCARTADO` e conclui o job (`CONCLUIDO`). Uma varredura (a mesma de 5 s da T12) repete o descarte dos jobs que ficaram `TRANSCRITO`.

**Estados novos do job:** `TRANSCRITO` (texto gravado, falta apagar o áudio), `CONCLUIDO` (gravado e apagado: o caminho feliz) e `FALHA_NO_WORKER` (o worker respondeu `FALHA`; o áudio é mantido, e um novo resultado `CONCLUIDO` do mesmo job ainda é aceito).

**Garantias (e como são testadas).**
- *Falha antes do commit mantém o áudio:* o arquivo só é tocado depois da transação que grava o texto. Resultado `FALHA`, mensagem inválida ou job desconhecido não apagam nada.
- *Falha depois do commit só repete o descarte:* se o disco não deixa apagar, o texto já está salvo, o job fica `TRANSCRITO` e a varredura insiste; só vira `CONCLUIDO` com **todos** os arquivos apagados (arquivo já ausente conta como apagado).
- *Idempotente:* a entrega repetida da fila (ou duas instâncias do backend) não duplica a `Transcricao` (única por consulta) nem quebra o descarte.
- *Caminho conferido:* o arquivo vem do banco, mas precisa ficar dentro de `{dir do sink}/{consultaId}/`; qualquer outro caminho é recusado e o job não conclui.
- *Arquivo ainda `GRAVANDO`:* é esperado até `esperaMaximaDoAudio` (10 min) depois do fim da consulta; passado o prazo, uma linha presa é tratada como apagável.
- *Fila não trava nem perde:* mensagem fora do contrato (JSON inválido, versão ≠ 1, UUID ruim, segmento incoerente) é registrada com um código fixo e descartada; falha inesperada (banco ou disco) repete a **mesma** mensagem a cada 5 s, sem limite, porque pular o offset perderia a transcrição.
- *Sem conteúdo em log nem auditoria:* só ids, tipos de exceção e `topic-partição@offset`. A auditoria registra `TRANSCRICAO_GRAVADA`, `TRANSCRICAO_FALHOU`, `AUDIO_DESCARTADO` e `TRANSCRICAO_LIDA`.

**Modelo.** `transcricao.texto` é o texto corrido (`Médico: …` / `Paciente: …`, em ordem de tempo); `segmentos` guarda as mesmas falas em JSON com papel, `inicioMs` e `fimMs`; `duracao_ms` e `idioma` completam (migração V6). O texto está duplicado entre `texto` e `segmentos` de propósito: a leitura simples não precisa de parse e o JSON preserva os tempos. Ambos são dado sensível de saúde.

**Leitura.** `GET /api/consultas/{id}/transcricao` só para o médico dono (consulta de outro médico é `404`, como em todo o resto). Cada leitura bem-sucedida é auditada com o ator e a consulta. O paciente não lê pela API; o acesso do titular aos próprios dados (LGPD art. 18) é um processo à parte, a definir com o jurídico.

**Limites / o que a T13 não faz.**
- O **áudio é apagado com `unlink`**, sem sobrescrita. Como o arquivo já é cifrado em repouso (T14), o que restar em blocos livres do disco é ilegível sem a chave mestra.
- O **texto permanece no tópico de resultados** até a retenção do broker (24 h por padrão, T14). Não é cifrado pela aplicação nesse trânsito: depende de TLS e disco cifrado do broker e de ACL.
- Um job `FALHA_NO_WORKER` ou `FALHA_NO_ENVIO` **não é reprocessado sozinho**; falta uma rotina ou comando de reenvio. O áudio dele não fica para sempre: a retenção (T14) o apaga depois de 7 dias e o job vira `EXPIRADO`.
- Uma única instância de consumidor por grupo; para escalar o backend, o tópico precisa de várias partições (T15).
- O worker, ao carregar o modelo, **consulta o huggingface.co** (verifica a revisão). Não envia dado de paciente, mas é saída para a internet: em produção, definir `HF_HUB_OFFLINE=1` com o modelo já baixado.

## Compliance: cifragem, auditoria e retenção (T14)

**Cifragem em repouso (na aplicação, AES-256-GCM).** Duas chaves de dados saem da chave mestra (`MEDILINK_DATA_KEY`, 32 bytes em Base64, obrigatória, fora do git) por HKDF-SHA256: a do texto e, para cada arquivo de áudio, uma própria (sal aleatório no cabeçalho). Assim o vazamento de uma não entrega a outra e nenhum par (chave, nonce) se repete.
- *Transcrição:* as colunas `transcricao.texto` e `segmentos` guardam `enc:<idDaChave>:<base64>` (conversor JPA); um dump ou backup do banco não mostra uma palavra da consulta. O id da chave é dado autenticado. Lê-se em claro só pela aplicação.
- *Áudio:* o sink grava `…opus.enc` no formato **MLA1**: cabeçalho (`MLA1`, id da chave, sal, tamanho do bloco) e blocos de 16 KiB `compr | cifrado+tag`, nonce = índice do bloco, cabeçalho autenticado (bloco trocado de lugar, cabeçalho de outro arquivo ou arquivo cortado no meio de um bloco **não decifra**). O worker (Python, `cryptography`) decifra **só em memória** e entrega ao Whisper; por padrão **recusa áudio sem cifra** (`MEDILINK_AUDIO_CIFRADO_OBRIGATORIO=0` desliga, só para arquivos antigos). Nunca há áudio claro no disco.
- *Rotação:* nova chave em `MEDILINK_DATA_KEY` com novo `MEDILINK_DATA_KEY_ID`; a antiga vai em `medilink.crypto.chaves-anteriores.<id>` (backend) e `MEDILINK_DATA_KEYS_ANTERIORES="id:base64"` (worker) enquanto houver dado com ela. O que for gravado dali em diante usa a nova. **Falta uma rotina que recifre o histórico** (`cifradoComChaveAtiva` já permite identificar o que ficou para trás).
- *Base antiga:* `medilink.crypto.aceitar-texto-legado=true` lê como texto puro o que não tem o prefixo `enc:` (só no perfil `dev`; em `prod` a subida é recusada se estiver ligado).
- *Quem guarda a chave:* o mesmo segredo está no backend e no worker. Em produção, cofre (Vault/KMS) e acesso por serviço; **perder a chave torna as transcrições ilegíveis** e nenhum backup do banco as recupera sem ela.
- *Compatibilidade Java ↔ Python* é testada nas duas direções com arquivos reais (`backend/src/test/resources/cifra/`, `worker/tests/fixtures/`).
- *O que a aplicação NÃO cifra:* nome do paciente, datas e metadados (`agendamento`, `consulta`, `audit_log`) e o texto em trânsito na fila. Cobrir isso é TDE/disco cifrado do banco (a Express do dev não tem TDE) e TLS na fila. O nome do paciente é dado pessoal e está em claro: item aberto abaixo.

**Auditoria.** `TRANSCRICAO_LIDA` (leitura), `TRANSCRICAO_ACESSO_NEGADO` (consulta de outro médico ou inexistente; gravado em transação própria, então sobrevive ao `404`) e os eventos de gravação, descarte e expiração. O CPF nunca é lido (só HMAC) e o áudio só é lido pelo worker, que não escreve no banco; por isso não há "leitura de CPF/áudio" a auditar além do enfileiramento. A tabela `audit_log` é **somente de inserção** no SQL Server (gatilho `INSTEAD OF UPDATE, DELETE`, migração V7): a aplicação e qualquer usuário comum do banco não reescrevem o histórico. Quem tem DDL pode remover o gatilho (deixa rastro no log do SQL Server). A retenção da auditoria em si é decisão do jurídico e se faz por procedimento administrativo.

**Retenção** (`medilink.retencao.*`, rotina de hora em hora, cada regra idempotente e isolada):

| Regra | Padrão | Efeito |
| --- | --- | --- |
| `tentativa-cpf` | 24 h | apaga as tentativas falhas de CPF (só servem à janela de 15 min do bloqueio) |
| `audio-maximo` | 7 dias | apaga o áudio ainda existente, **qualquer que seja o estado do job**; o job que dependia dele vira `EXPIRADO` (`410` na API) |
| `transcricao` | `0s` = guardar | se houver prazo, apaga a transcrição vencida (`TRANSCRICAO_EXPIRADA`; `410` na API) |
| `retencao-dos-topicos` | 24 h | `retention.ms` dos dois tópicos da fila (aplicado também a tópicos já existentes na subida) |

Os prazos são **pontos de partida técnicos**. O prazo da transcrição depende de ela ser parte do prontuário (prazos de guarda do prontuário são longos): decidir com o jurídico/DPO antes de produção.

**TLS interno: o que é imposto e o que é só configuração.**
- *Imposto (testado):* no perfil `prod` a aplicação **não inicia** sem `encrypt=true` e sem `trustServerCertificate=true` no banco, `server.ssl.enabled=true` (HTTPS/WSS, inclusive o sink) e `spring.kafka.security.protocol` SSL/SASL_SSL, nem com `aceitar-texto-legado` ligado (`ExigenciasDeProducao`). As mensagens citam só nomes de propriedades.
- *Configurado, **não exercitado** (sem certificados reais aqui):* `application-prod.properties` (keystore do servidor, truststore do Kafka), o `url-template` do Jicofo em `wss://` com a CA no truststore do Jicofo, e o worker (`MEDILINK_KAFKA_SECURITY_PROTOCOL=SSL` + `MEDILINK_KAFKA_CA`, testado só na montagem dos parâmetros). Dev continua em texto puro.

**Verificação.** Backend 163 testes (SQL Server local, Redpanda real): cifra (11), retenção (7), auditoria, tópicos com retenção no broker de verdade, exigências de produção. Worker 39. Ponta a ponta com Jitsi real (`verify_t11.py`: os arquivos no disco começam por `MLA1`, não têm `OggS`/`OpusHead` e decifram para Ogg Opus válido; `verify_t13.py` 14/14 com o worker lendo o áudio cifrado). Não verificado: TLS real, chave em cofre, backup/restauração com a chave, rotação em dados existentes.

**Limites / pendências da T14.**
- Nome do paciente e metadados em claro (ver acima); backups e TDE são da infraestrutura.
- Sem rotina que recifre o histórico depois de rotacionar a chave.
- A chave mestra única serve ao backend e ao worker; separar por serviço (e usar KMS com envelope) é o passo seguinte.
- O login do médico (Basic provisório) não é auditado; entra com o IdP definitivo.
- Nenhum controle de acesso por papel além de médico dono; um perfil administrativo/DPO para consultar a trilha não existe (a leitura da `audit_log` é por SQL).
- O ajuste de retenção dos tópicos é aplicado na subida do backend; se o broker estiver fora nessa hora, só avisa.

## Proposta: integração por API key, acesso do médico, painel e webhook (T16 a T19)

**Status:** decisões tomadas em 2026-10-30; nada implementado ainda, exceto a regra do consentimento obrigatório (seção "Consentimento", acima). Origem: integrar a plataforma a outros sistemas (agendar, editar, cancelar, iniciar a sala, devolver a transcrição) e observar o andamento.

**Princípios de segurança que valem para tudo abaixo**
- API key só no cabeçalho `X-API-Key` (nunca na URL), 256 bits aleatórios, mostrada uma vez e guardada só como hash; uma por sistema integrado, revogável, com escopos, limite de uso e auditoria (`ator = api-key/<id>`). Chamada só servidor a servidor, sem CORS.
- **Isolamento entre sistemas integrados:** cada agendamento pertence ao sistema que o criou; operação de outro sistema responde `404`. A "chave de identificação" do médico é única dentro do sistema integrado.
- A **chave da reunião** devolvida é um id próprio, diferente do `roomName` (que é o segredo do link do paciente).
- O painel e as respostas da API nunca expõem CPF, e-mail, nome do paciente nem texto de transcrição (exceto o webhook, abaixo).

**T16 — API key e agendamentos.** `POST` cria (médico: nome e chave; agendamento: chave do sistema de origem para idempotência, data e horário com fuso explícito; paciente: nome, e-mail, CPF opcional — se informado, a validação de CPF passa a ser obrigatória; `notificar` envia o link por e-mail se houver e-mail) e devolve a **chave da reunião** e o **link do paciente**. `PUT/PATCH` altera enquanto a consulta não começou (o link não muda; não troca de médico). `DELETE` **cancela** (o link para de funcionar) e só antes de a consulta começar; depois disso, ou havendo transcrição, `409`: a retenção é política da plataforma, não do sistema integrado. Limite de requisições por key, corpo e horizonte de data máximos, e-mail só com modelo fixo e limite por key e destinatário. Nome e e-mail do paciente passam a ser cifrados em repouso.

**T17 — Acesso do médico.** O link do médico **não** nasce na criação do agendamento: o sistema integrado chama o endpoint de iniciar a sala (nome e chave do médico + chave da reunião) quando o médico clica em iniciar, e recebe um **link de uso único** (poucos minutos, token no fragmento da URL, amarrado à sala e ao médico), que a plataforma troca por uma **sessão restrita àquela consulta** (aceitar, encerrar, ler a transcrição). Pode ser chamado de novo quantas vezes for preciso (o último link vale); só emite numa janela em torno do horário agendado (padrão: de 30 min antes a 12 h depois); com a consulta encerrada, emite um link que só abre a leitura. **Janela de tolerância** (padrão 10 min): depois de encerrada por engano ou queda, o médico pode reabrir; o job de transcrição só fica elegível depois da janela. O **link do paciente passa a expirar** (horário agendado + tolerância, padrão 24 h, prorrogável por edição). Encerramento por inatividade (servidor) entra aqui.

**T18 — Administração e painel.** Usuário administrador próprio (senha forte + TOTP), registrado em auditoria. Painel só com contagens e estados: agendamentos por status e período, jobs de transcrição por status, falhas, áudio pendente, **consultas encerradas sem áudio (incidente)**, entregas de webhook, uso por sistema integrado. Cadastro e revogação de API keys e de sistemas integrados (incluindo host e segredo do webhook). **Seção do termo de consentimento:** versões em banco (rascunho → vigente → arquivada), publicada é imutável (alterar = nova versão), com hash do texto gravado junto do consentimento e auditoria de quem publicou; o texto é exibido como texto puro (sem HTML). A versão 2 atual vira a primeira linha dessa tabela.

**T19 — Webhook de devolução.** Opcional por agendamento; sem webhook o fluxo é o de hoje.
- *Destino:* o administrador cadastra, por sistema integrado, a **URL base** (HTTPS) e o segredo de assinatura. Cada agendamento envia só **caminho e parâmetros** (para direcionar clientes diferentes): relativos à base, validados (começa por `/`, sem `..`, sem `//`, sem `@`, sem barra invertida, sem CRLF nem fragmento, tamanho e quantidade limitados) e, depois de montada, a URL final precisa ter **o mesmo esquema, host e porta da base** e o caminho sob o prefixo da base. Caminho e parâmetros podem carregar segredo do cliente: ficam cifrados e nunca vão para log (só o host).
- *SSRF:* só HTTPS com certificado verificado (HTTP apenas em dev), sem usuário na URL, DNS resolvido e **IP privado, loopback, link-local e metadados bloqueados** com o IP fixado na conexão (contra rebinding), sem seguir redirecionamentos, timeout e tamanho limitados. A URL só pode ser alterada antes de a consulta começar (auditado).
- *Autenticidade:* assinatura HMAC-SHA256 do corpo com carimbo de tempo e id de evento (entrega "pelo menos uma vez": o receptor deduplica).
- *Entrega:* tabela de saída (outbox) que guarda só a referência (o corpo é montado na hora a partir da transcrição já cifrada); recuo crescente (1 min, 5 min, 30 min, 2 h, 6 h, 24 h); 2xx conclui, 5xx e 429 repetem, outros 4xx desistem; falhas visíveis no painel, com reenvio manual.
- *Conteúdo (modo completo, padrão):* chave do agendamento, chave da reunião, médico (chave), data e horário, status, duração, idioma e os segmentos (papel, tempos, texto). **Sem CPF, e-mail nem nome do paciente.** Eventos: `transcricao.concluida` e `transcricao.indisponivel` (com o motivo). Um modo `referencia` (só o evento e o id; o receptor busca o texto por API autenticada) fica como opção por sistema integrado.
- *Retenção:* a plataforma **mantém a cópia pelo prazo de retenção configurado** (`medilink.retencao.transcricao`); transcrição ainda não entregue nunca é apagada pela retenção.
- *Termo:* considerado já implementado e gerido pelo painel (T18); o texto que informa o envio a outro sistema é responsabilidade de quem publica o termo.

**Ordem sugerida:** T16 → T17 → T18 → T19 → T15. A T19 depende do cadastro de sistemas integrados (T16/T18).

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
| POST | `/api/consultas/{id}/consentimento` | Registra, recusa ou retira o consentimento (paciente). Recusar ou não consentir bloqueia a chegada à espera (`POST /api/salas/{room}/token` → `409`) | T09 |
| POST | `/api/consultas/{id}/aceite` | Host admite o paciente | T08 |
| POST | `/api/consultas/{id}/encerrar` | Médico encerra a consulta em andamento (idempotente) e cria o job de transcrição | T10/T12 |
| POST | `/api/consultas/{id}/participante` | O cliente informa o próprio endpoint do Jitsi (médico autenticado; paciente com o token de entrada) | T12 |
| GET | `/api/me` | Nome e e-mail do médico autenticado (valida o login) | T10 |
| GET | `/api/consultas/{id}/transcricao` | Transcrição da consulta (só o médico dono): `200` com falantes e tempos; `202` enquanto processa; `409` se a consulta está aberta ou a transcrição falhou; `404` se a consulta não é dele ou não teve áudio; `410` se o áudio ou a transcrição expiraram pela retenção | T13/T14 |

**JWT do Jitsi:** médico com `moderator=true`; paciente sem moderação, emitido só após o CPF (quando ativo). Claims amarram `agendamentoId`, `medicoId` e o paciente. Sala de espera = lobby do Jitsi.

## Idempotência do descarte (T13) — regra de projeto

Implementada e verificada: veja "Gravação da transcrição e descarte do áudio (T13)", acima.

1. Gravar `Transcricao` (unique por `consultaId`) numa transação.
2. Só após o commit, apagar o áudio; a remoção é idempotente (arquivo ausente = sucesso).
3. Falha em qualquer ponto antes do commit mantém o áudio e permite reprocessar; falha depois do commit apenas repete o passo 2.
4. O job só termina com sucesso com `Transcricao` persistida e áudio removido.

## Decisões em aberto

Relay de e-mail em produção (SMTP próprio × serviço); SLA da transcrição (minutos × horas) e nº de GPUs; pico de 1.000 sustentado ou raro; modelo Whisper e terminologia médica; relay de e-mail; política de retenção; **IdP/login do médico**.
