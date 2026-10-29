# MediLink: frontend

Telas do médico e do paciente, em Angular 21 (componentes standalone, sem zone.js, signals). A visão geral do projeto está no [README da raiz](../README.md).

## Rotas

| Rota | Quem | O que faz |
| --- | --- | --- |
| `/sala/:roomName` | paciente | CPF (se o médico exigir), termo de consentimento, espera do aceite e sala |
| `/medico` | médico | Login (HTTP Basic provisório; credenciais só em memória) |
| `/medico/agendar` | médico | Cria o agendamento, copia o link e envia por e-mail |
| `/medico/consulta/:agendamentoId` | médico | Sala do host: aceita o paciente e, ao sair, encerra a consulta |

## Executar

```bash
npm install       # o .npmrc usa legacy-peer-deps (bug do npm 10.8.2 com peer deps)
npx ng serve      # http://localhost:4200; o proxy.conf.json leva /api ao backend em :8090
npx ng test --watch=false
npx ng build
```

O Jitsi é embutido pela IFrame External API (`src/app/core/jitsi-room.ts`). O endereço do Jitsi e a E2EE ficam em `src/app/core/config.ts`. Em desenvolvimento o Jitsi usa certificado autoassinado: abra `https://localhost:8443` uma vez e aceite.

## Pontos de atenção

- O paciente só entra na sala admitido pelo médico **e** depois de responder ao termo.
- O CPF só trafega no corpo de um `POST`; nunca na URL nem em armazenamento do navegador.
- A criptografia de ponta a ponta está **desligada por padrão** (`E2EE_ATIVA_POR_PADRAO`): a captura do áudio no servidor depende disso. Veja `docs/plan.md`.
