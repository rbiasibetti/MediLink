# Jitsi self-hosted (dev)

```bash
./dev.sh init      # cria .env com segredos aleatórios e as pastas de configuração
./dev.sh up        # web, prosody, jicofo, jvb (docker compose ou podman compose)
./dev.sh env       # variáveis MEDILINK_JITSI_* que o backend precisa
```

Com podman rootless, o `docker-compose` precisa do socket: `systemctl --user start podman.socket`.

Verificar o T02 (precisa do backend em `dev` rodando, veja `backend`):

```bash
python3 -I verify_auth.py <sala> <jwt-da-sala>
```

**Captura de áudio (T11):** `prosody-plugins/` (módulo Lua), `custom-jicofo.conf.template` e `e2e/` (verificação com Chrome headless). O `dev.sh up` instala tudo; `SINK_WS_URL` e `SINK_TOKEN` ficam no `.env`.

**Não exposto:** a interface do Jitsi só carrega dentro do iframe do MediLink (`nginx-custom/` bloqueia navegação direta; `CSP_HEADER` limita quem embute). O `dev.sh up` instala essas regras.

O `docker-compose.yml` vem de https://github.com/jitsi/docker-jitsi-meet (Apache-2.0), sem alterações.
