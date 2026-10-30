# Jitsi self-hosted

Jitsi Meet (web, Prosody, Jicofo e JVB) configurado para o MediLink. O `docker-compose.yml` vem de https://github.com/jitsi/docker-jitsi-meet (Apache-2.0), **sem alterações**; tudo o que é do MediLink entra por arquivos de configuração e variáveis de ambiente. A visão geral e o passo a passo de produção estão no [README da raiz](../../README.md#8-jitsi).

## Desenvolvimento

```bash
./dev.sh init      # cria .env com segredos aleatórios e as pastas de configuração
./dev.sh up        # instala as regras do MediLink e sobe web, prosody, jicofo, jvb (docker compose ou podman compose)
./dev.sh down      # para tudo
./dev.sh logs      # acompanha os logs
./dev.sh env       # variáveis MEDILINK_JITSI_* e MEDILINK_SINK_TOKEN que o backend precisa
```

Com podman rootless, o `docker-compose` precisa do socket (`systemctl --user start podman.socket`); o `dev.sh up` faz o `podman unshare chown` das pastas.

O certificado de desenvolvimento é autoassinado: abra `https://localhost:8443` uma vez e aceite. A página mostra "Acesso direto bloqueado", que é o esperado.

## O que o `dev.sh up` instala

| Origem (este diretório) | Destino (`${CONFIG}`) | Para quê |
| --- | --- | --- |
| `nginx-custom/10-bloquear-acesso-direto.conf` | `web/nginx-custom/` | `Sec-Fetch-Dest: document` recebe 403; só o iframe do MediLink carrega a interface |
| `custom-config.js` | `web/custom-config.js` | Interface mínima (sem chat, enquete, reações, convites, prejoin, P2P) |
| `prosody-plugins/mod_medilink_transcription.lua` | `prosody/prosody-plugins-custom/` | Marca toda sala para exportar o áudio (`asyncTranscription`) e passa o `roomName` ao sink |
| `custom-jicofo.conf.template` | `jicofo/custom-jicofo.conf` (gerado) | URL do sink (`SINK_WS_URL`) e cabeçalho `Authorization: Bearer` (`SINK_TOKEN`) |
| (pasta vazia) | `web/custom-ca`, `jvb/custom-ca` | CA privada, se o certificado do backend não for de CA pública |

O `.env.example` documenta cada variável. As que mais importam: `AUTH_TYPE=jwt`, `ENABLE_GUESTS=0`, `JWT_APP_SECRET`, `XMPP_MUC_MODULES=token_affiliation,medilink_transcription`, `ENABLE_P2P=0`, `CSP_HEADER`, `SINK_WS_URL`, `SINK_TOKEN`, `JVB_ADVERTISE_IPS` e `JITSI_IMAGE_VERSION` (sempre fixa).

## Verificações

Precisam do backend em `dev` rodando (veja o [README da raiz](../../README.md#desenvolvimento-local)):

```bash
python3 -I verify_auth.py <sala> <jwt-da-sala>        # sem token, token inválido ou de outra sala: recusado
python3 -I verify_t07.py ...                          # médico moderador, paciente sem moderação
python3 -I verify_t08.py ...                          # paciente só entra depois do aceite
python3 -I e2e/verify_t11.py 'medico@medilink.local:dev-only-change-me'   # captura com Chrome headless
```

A regra de acesso direto se confere com `curl`: sem o cabeçalho `200`, com `Sec-Fetch-Dest: document` `403`, com `iframe` `200`.

## Produção

Use os mesmos arquivos, com estes ajustes no `.env`: `CONFIG` fora do repositório, `HTTP_PORT=80`, `HTTPS_PORT=443`, `PUBLIC_URL=https://meet.…`, `JVB_ADVERTISE_IPS` com o **IP público**, `CSP_HEADER` com a origem do app, `SINK_WS_URL` em `wss://` apontando para o backend e, se quiser, `ENABLE_LETSENCRYPT=1`. Libere `80/tcp`, `443/tcp` e `10000/udp` e feche o resto. Se o certificado do backend for de CA privada, coloque o `.crt` em `${CONFIG}/jvb/custom-ca/`. Passo a passo completo, incluindo os comandos equivalentes ao `dev.sh up` para gestão de configuração, em [Implantação em produção › Jitsi](../../README.md#8-jitsi).

Ao atualizar `JITSI_IMAGE_VERSION`, repita as verificações: a regra de nginx, o módulo Lua e o `custom-config.js` dependem da versão da imagem.

> O Prosody registra o JWT inteiro em erros de verificação de token. Trate os logs dele como sensíveis (acesso restrito e rotação).
