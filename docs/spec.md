# Especificação — MediLink (telemedicina sobre Jitsi)

Fonte da verdade do **comportamento** (o QUE e o PORQUÊ). Detalhes de implementação ficam em `plan.md`.

## Objetivo e escopo

Plataforma de telemedicina self-hosted sobre o Jitsi: agendamento, link de reunião predefinido, validação do paciente por CPF (desativável pelo médico), aceite do paciente pelo médico-host e transcrição automática pós-consulta gravada no banco, com descarte do áudio ao final.

Premissas: infraestrutura própria (sem terceiros para dado sensível); transcrição assíncrona e apenas de áudio; até 1.000 consultas simultâneas no pico; backend Spring Boot, frontend Angular.
Fora de escopo: gravação de vídeo e transcrição em tempo real.

## Requisitos funcionais

| ID | Requisito | Critério de aceite |
| --- | --- | --- |
| RF-01 | **Agendamento.** O médico informa nome e CPF do paciente, e-mail (opcional), data/hora e se a validação de CPF está ativa. O sistema gera um link de reunião predefinido. | Após criar, existe um link único e persistente, com o médico como host. |
| RF-02 | **Link predefinido.** O link aponta para uma sala fixa da consulta, criada antes do horário. O host é o médico responsável. | Abrir o link leva à sala de espera; o médico entra como moderador. |
| RF-03 | **Envio por e-mail.** O link pode ser enviado ao paciente a partir do agendamento. | Ao acionar o envio, o paciente recebe o e-mail com o link e o envio é registrado. |
| RF-04 | **Validação de CPF (desativável).** Com a validação ativa, o paciente confirma o CPF e ele deve coincidir com o do agendamento. O médico pode desativar a etapa. | Ativa: CPF divergente bloqueia, correto libera para a sala de espera. Desativada: o paciente acessa sem informar CPF. |
| RF-05 | **Aceite pelo host.** O paciente aguarda em sala de espera; o médico o admite. | O paciente só entra na sala após o aceite explícito do médico. |
| RF-06 | **Captura e transcrição pós-consulta.** O áudio é capturado durante a consulta e transcrito de forma assíncrona após o encerramento. | Encerrada a consulta, um job de transcrição é enfileirado com referência ao áudio. |
| RF-07 | **Transcrição no banco e descarte do áudio.** Concluída a transcrição, o texto é gravado em registro vinculado à consulta e o áudio é descartado. | Existe registro de transcrição no banco e o arquivo de áudio não existe mais em nenhum armazenamento. |
| RF-08 | **Consentimento.** O consentimento de gravação/transcrição é coletado e registrado antes do início. | Sem consentimento registrado, a captura não inicia. |

## Domínio

| Entidade | Campos principais |
| --- | --- |
| Medico | id, nome, identificador profissional, e-mail |
| Agendamento | id, medicoId (host), pacienteNome, pacienteCPF, pacienteEmail (opcional), dataHora, roomName (link), cpfValidationEnabled, status |
| Consulta | id, agendamentoId, inicioEm, fimEm, consentimentoEm, status |
| Transcricao | id, consultaId, texto, idioma, criadaEm |
| AuditLog | id, ator, acao, recurso, timestamp |

Regras de dados:

- CPF é dado pessoal: armazenado com proteção; a confirmação compara de forma segura, sem expor o valor em logs nem na URL.
- Não há armazenamento permanente de áudio: ele existe só como arquivo transitório até a transcrição concluir (RF-07).
- `Transcricao` é o artefato persistente do conteúdo falado e compõe o prontuário.

## Compliance (parte da especificação)

Orientação de engenharia, não aconselhamento jurídico — validar com jurídico e DPO.

- LGPD art. 11: áudio e transcrição são dado sensível de saúde; base legal, registro de tratamento e processamento dentro da infraestrutura.
- CPF: limitar tentativas, evitar enumeração (não revelar se um CPF existe), nunca logar nem colocar em URL.
- Resolução CFM 2.314/2022: documentação do atendimento, confidencialidade e identificação das partes.
- Consentimento registrado antes da captura. Minimização pelo descarte do áudio.
- Criptografia em trânsito e em repouso; auditoria de acesso a áudio, CPF e transcrição; política de retenção; controle de acesso por papel e JWT por sala.
