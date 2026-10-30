# Tarefas — MediLink

Uma por vez; só avança quando o critério de aceite (CA) passa. Estados: `todo`, `doing`, `done`, `blocked`.

| ID | Fase | Tarefa | CA | Dep. | Estado |
| --- | --- | --- | --- | --- | --- |
| T01 | Fundação | Subir Jitsi self-hosted (Meet, Prosody, Jicofo, JVB) em dev | Sala de teste abre no navegador | — | **done** (podman: 4 serviços no ar, web 200, Jicofo cria a sala; a tela no navegador não foi aberta pelo agente) |
| T02 | Fundação | Autenticação JWT no Jitsi | Só entra quem porta token válido | T01 | **done** (`infra/jitsi/verify_auth.py`: 5/5 cenários contra Prosody real, token emitido pelo backend) |
| T03 | Agendamento | Modelo de dados + migrações | Migrações aplicam; testes de repositório passam | — | **done** (`./mvnw test`: 18 testes verdes) |
| T04 | Agendamento | Endpoint de agendamento com link predefinido | POST cria e retorna link; host = médico | T03 | **done** (`./mvnw test`: 18 testes verdes) |
| T05 | Agendamento | Envio do link por e-mail | E-mail enviado e registrado | T04 | **done** (6 testes; verificado com SMTP real via `infra/dev/smtp_sink.py`) |
| T06 | Acesso | Tela de acesso + validação de CPF (desativável) | Divergente bloqueia; correto libera; flag off pula | T04 | **done** (backend: 15 testes, incl. bloqueio após 5 falhas; tela Angular de acesso criada na T10 e exercitada no navegador: CPF errado barra, correto libera) |
| T07 | Acesso | JWT: host e paciente após validação | Paciente só obtém token após T06; médico é moderador | T02, T06 | **done** (8 testes de API; `infra/jitsi/verify_t07.py` 11/11 contra Prosody real: médico owner/moderator, paciente member/participant) |
| T08 | Consulta | Lobby e aceite pelo host | Paciente entra só após aceite | T07 | **done** (10 testes de API; `infra/jitsi/verify_t08.py` 9/9 contra Prosody real, incl. paciente chegando antes do médico). Aceite no backend, não no lobby do Jitsi: ver plan.md |
| T09 | Consulta | Consentimento antes da captura | Sem consentimento, não captura | T08 | **done** (13 testes de API no SQL Server local; o portão `capturaAutorizada` será consumido pelo audio sink na T11) |
| T10 | Consulta | Embed Angular + evento de encerramento | Ao sair, consulta marcada como encerrada | T08 | **done no código e nos testes** (backend 5 testes de encerramento; Angular 19 testes; jornada paciente+médico exercitada no navegador contra o backend real). **Não verificado:** o embed real do Jitsi e o evento `videoConferenceLeft` num navegador, porque o certificado autoassinado de `localhost:8443` não é aceito aqui; precisa de um teste seu (abrir `https://localhost:8443` e aceitar o certificado) |
| T11 | Transcrição | Audio sink (bridge) com Opus transitório por sala | Áudio disponível como arquivo transitório | T10 | **done** (decisão em plan.md: E2EE desligada, P2P desligado, captura no servidor). Backend 102 testes no SQL Server local (19 do sink); frontend 33; **ponta a ponta com Jitsi real** (`infra/jitsi/e2e/verify_t11.py`): com consentimento, 1 Ogg Opus válido por participante, nada antes do aceite, linha do tempo coerente; sem consentimento, zero arquivos. Limites: o gatilho é o cliente do médico (endurecer no servidor), falta mapear fonte → médico/paciente (T12), descarte é a T13, produção exige `wss` e disco cifrado (T14) |
| T12 | Transcrição | Fila + worker faster-whisper | Transcreve pt-BR, identifica locutores, VAD | T11 | **done** — backend 118 testes (SQL Server local + Redpanda real), worker 30, frontend 38; **ponta a ponta com Jitsi real e fala em português** (`infra/jitsi/e2e/verify_t12.py`): médico e paciente falam trechos diferentes, o resultado chega ao tópico em `pt`, cada trecho com o papel certo e na ordem certa; sem consentimento nada é transcrito. Detalhes e limites: `docs/plan.md` ("Transcrição pós-consulta") |
| T13 | Transcrição | Gravar `Transcricao` e descartar áudio (idempotente) | Registro existe e áudio some; falha mantém o áudio | T12 | **done** — backend 136 testes (SQL Server local + Redpanda real; 18 novos: serviço 9, API 7, consumidor 2); **ponta a ponta com Jitsi real e o worker** (`infra/jitsi/e2e/verify_t13.py`, 14/14): a API devolve a transcrição com médico e paciente nos rótulos certos e o áudio some do disco; sem consentimento, 404 e nenhuma pasta. Detalhes e limites: `docs/plan.md` ("Gravação da transcrição e descarte do áudio") |
| T14 | Compliance | Criptografia em repouso, auditoria, retenção | Acessos auditados; áudio/transcrição cifrados | T13 | **done** — backend 163 testes, worker 39; **áudio cifrado no disco e lido pelo worker em memória, texto cifrado nas colunas**, trilha de auditoria só de inserção, retenção (tentativas de CPF, teto de guarda do áudio, transcrição opcional, tópicos da fila) e `prod` que recusa subir sem TLS verificado. Ponta a ponta com Jitsi real (`verify_t11.py`, `verify_t13.py` 14/14). **Limites:** TLS real não exercitado; backups/TDE e KMS são da infraestrutura; nome do paciente em claro; sem rotina de recifragem. Detalhes: `docs/plan.md` ("Compliance: cifragem, auditoria e retenção") |
| T15 | Escala | Múltiplos JVBs, autoscaling, carga 100→500→1.000 | Métricas atingem o alvo | T13 | todo |

Concluídas: T01–T14. Próxima: T15 (escala: vários JVBs, autoscaling, teste de carga). A T12, a T13 e a T14 foram verificadas com Chrome de teste e áudio de arquivo; falar de verdade num navegador com microfone real continua sem verificação.

Banco: sem H2; a suíte completa (163 testes) roda só contra o SQL Server local (`backend/run-mssql.sh test`); pendência: rodar em uma instância 2019 exata. Jitsi não exposto (bloqueio de navegação direta + CSP): verificado por curl com os cabeçalhos do navegador e pelos scripts de verificação; **o iframe real com essas regras ainda precisa ser conferido num navegador**. Restrições e preferências da sala (ruído, nível 2, recursos desabilitados; E2EE desligada por padrão e P2P desligado): config servida e JWT verificados, 26 testes de frontend e regressão no Prosody; **o comportamento real no navegador com mídia (nível 2, ruído e o P2P desligado) não foi verificado**. Outras pendências: o Prosody registra o JWT completo nos logs de erro de `token_verification` — tratar logs do Prosody como sensíveis (token é por sala e expira em 2 h).

## Backlog mapeado para a próxima sequência

Nada abaixo foi iniciado. Está registrado aqui e no `README.md` para retomar depois.

### Etapa do plano ainda não feita (T15)

| ID | O que falta | Pontos de atenção já levantados |
| --- | --- | --- |
| T13 | **Feito** (ver acima) | Pendências que sobraram dela estão em "Endurecimentos", abaixo |
| T14 | **Feito** (ver acima) | Pendências que sobraram dela estão em "Endurecimentos", abaixo |
| T15 | Escala: vários JVBs, autoscaling dos workers, teste de carga 100 → 500 → 1.000 | P2P desligado leva toda a mídia ao JVB (≈ 2.000 streams para 1.000 salas); o `url-template` do sink precisa de balanceador; limite de tamanho por arquivo |

### Endurecimentos e pendências levantados durante a implementação

- **Gatilho da captura no servidor:** hoje o cliente do médico liga a exportação (e o sink garante o consentimento). Mover para o Prosody/backend, a partir do estado do consentimento, para a captura não depender do navegador.
- **Encerramento por inatividade:** fechar a aba sem sair da conferência deixa a consulta em andamento.
- **Login definitivo do médico:** hoje é HTTP Basic provisório (decisão em aberto: IdP).
- **Token de entrada de 10 min:** se o aceite do médico demorar mais, o paciente precisa repetir o CPF; renovar durante a espera.
- **Resultado da transcrição:** a T13 grava no banco (cifrado desde a T14); o texto passa pelo tópico `medilink.transcricao.resultados` (retenção de 24 h, TLS e ACL são do broker). O worker roda no host (falta container) e a transcrição custa ≈ 2× o tempo real em CPU (dimensionar na T15).
- **Reprocessamento de jobs falhos:** `FALHA_NO_ENVIO` e `FALHA_NO_WORKER` mantêm o áudio até o teto de 7 dias (depois `EXPIRADO`), mas nada os reenvia sozinho: falta o comando de reenvio.
- **Compliance que sobrou da T14:** nome do paciente e metadados em claro no banco; TDE/backup cifrado e KMS (chave única para backend e worker hoje); rotina que recifre o histórico após rotacionar a chave; TLS real (certificados, `wss` no Jicofo, Kafka SSL) só está configurado e testado na montagem, não exercitado; login do médico e perfil de DPO para consultar a trilha; prazo de retenção da transcrição e da auditoria (jurídico).
- **Worker e internet:** ao carregar o modelo o worker consulta o huggingface.co (sem dado de paciente); com o modelo já baixado, `HF_HUB_OFFLINE=1` evita a consulta (verificado na T14).
- **Acesso do titular à transcrição** (LGPD art. 18): hoje só o médico lê pela API; o fluxo para o paciente pedir os próprios dados é do jurídico/DPO.
- **Mapa de participantes é informado pelo cliente** (o papel vem da autenticação, mas o endpoint vem do navegador); um mapa autoritativo exigiria o Prosody informar `nick → usuário`.
- **Vocabulário médico:** avaliar a qualidade do Whisper com terminologia clínica e decidir sobre glossário ou outro modelo.
- **Tela Angular de agendamentos:** não há listagem/edição/cancelamento de agendamentos (só criar).
- **Relay de e-mail de produção** (SMTP próprio ou serviço) e rate limit de reenvio.
- **Produção:** certificado válido e HTTPS forçado, `wss` até o sink, segredos em cofre, `trustServerCertificate=false`.
- **Verificação com pessoas reais:** vídeo e áudio num navegador com câmera e microfone de verdade (só foi verificado com Chrome de teste e microfone falso); nível de desempenho 2, supressão de ruído e P2P desligado.
- **Banco:** rodar em SQL Server 2019 exato (o desenvolvimento usou o 2022 Express).
- **Validação jurídica:** termo de consentimento (minuta), leitura do texto oficial da Resolução CFM 2.314/2022 e conclusão do DPO sobre a decisão de não usar E2EE.
- **Decisões em aberto** (ver `docs/plan.md`): SLA da transcrição e nº de GPUs, pico sustentado de 1.000 salas, modelo Whisper e terminologia médica, política de retenção da transcrição.
