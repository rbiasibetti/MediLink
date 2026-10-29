#!/usr/bin/env bash
# Operação do Jitsi de desenvolvimento. Funciona com docker compose ou podman compose.
#   ./dev.sh init   cria .env com segredos aleatórios e as pastas de configuração
#   ./dev.sh up     instala as regras (nginx e custom-config.js) e sobe web, prosody, jicofo e jvb
#   ./dev.sh down   derruba (mantém a configuração)
#   ./dev.sh logs   acompanha os logs
#   ./dev.sh env    imprime as variáveis que o backend precisa (MEDILINK_JITSI_*)
set -euo pipefail
cd "$(dirname "$0")"

if command -v docker >/dev/null 2>&1; then COMPOSE=(docker compose)
elif command -v podman >/dev/null 2>&1; then COMPOSE=(podman compose)
else echo "Instale docker ou podman." >&2; exit 1; fi

segredo() { openssl rand -hex 16; }

# Podman rootless: o uid 1000 do container é um uid mapeado, não o do host. Sem isto os volumes de
# configuração ficam "not writable by the container user".
ajustar_permissoes() {
  if [ "${COMPOSE[0]}" = podman ]; then
    podman unshare chown -R 1000:1000 "$(grep '^CONFIG=' .env | cut -d= -f2)"
  fi
}

preencher() { # preencher CHAVE VALOR: só substitui se estiver vazia
  local chave=$1 valor=$2
  sed -i "s#^${chave}=\$#${chave}=${valor}#" .env
}

# Regras e configuração do MediLink sobre o Jitsi. O init do container copia ${CONFIG}/web para
# /run/web/config: o meet.conf inclui nginx-custom/*.conf dali, e custom-config.js é anexado ao config.js.
instalar_regras_jitsi() {
  local base prefixo=()
  base="$(grep '^CONFIG=' .env | cut -d= -f2)/web"
  # Com podman rootless a pasta pertence ao uid mapeado do container; "podman unshare" age como esse dono.
  [ "${COMPOSE[0]}" = podman ] && prefixo=(podman unshare)
  "${prefixo[@]}" mkdir -p "$base/nginx-custom"
  "${prefixo[@]}" sh -c "rm -f '$base'/nginx-custom/*.conf && cp nginx-custom/*.conf '$base'/nginx-custom/ && cp custom-config.js '$base'/custom-config.js"

  # Jicofo: o template recebe a URL e o token do audio sink (do .env). O segredo não vai para o repositório.
  local cfg url token jic
  cfg="$(grep '^CONFIG=' .env | cut -d= -f2)"
  url="$(grep '^SINK_WS_URL=' .env | cut -d= -f2-)"
  token="$(grep '^SINK_TOKEN=' .env | cut -d= -f2-)"
  jic="$(mktemp)"
  sed -e "s#__SINK_WS_URL__#${url}#" -e "s#__SINK_TOKEN__#${token}#" custom-jicofo.conf.template > "$jic"
  "${prefixo[@]}" mkdir -p "$cfg/jicofo"
  "${prefixo[@]}" cp "$jic" "$cfg/jicofo/custom-jicofo.conf"
  rm -f "$jic"

  # Plugins Lua do Prosody (a imagem monta ${CONFIG}/prosody/prosody-plugins-custom em /prosody-plugins-custom)
  "${prefixo[@]}" mkdir -p "$cfg/prosody/prosody-plugins-custom"
  "${prefixo[@]}" sh -c "cp prosody-plugins/*.lua '$cfg'/prosody/prosody-plugins-custom/"
}

case "${1:-}" in
  init)
    [ -f .env ] && { echo ".env já existe; nada a fazer."; exit 0; }
    cp .env.example .env
    chmod 600 .env
    for k in JICOFO_AUTH_PASSWORD JVB_AUTH_PASSWORD JIGASI_XMPP_PASSWORD JIGASI_TRANSCRIBER_PASSWORD \
             JIBRI_RECORDER_PASSWORD JIBRI_XMPP_PASSWORD; do preencher "$k" "$(segredo)"; done
    # HS256 pede >= 256 bits: 64 hex = 256 bits.
    preencher JWT_APP_SECRET "$(segredo)$(segredo)"
    preencher SINK_TOKEN "$(segredo)$(segredo)"
    cfg=$(grep '^CONFIG=' .env | cut -d= -f2)
    mkdir -p "$cfg"/{web/custom-ca,storage/web,storage/transcripts,tmp/web-load-test,prosody/config,prosody/prosody-plugins-custom,jicofo,jvb,jigasi,jibri}
    echo ".env criado. Rode ./dev.sh up"
    ;;
  up)   instalar_regras_jitsi; ajustar_permissoes; "${COMPOSE[@]}" up -d web prosody jicofo jvb ;;
  down) "${COMPOSE[@]}" down ;;
  logs) "${COMPOSE[@]}" logs -f --tail=100 ;;
  env)
    set -a; . ./.env; set +a
    echo "export MEDILINK_JITSI_APP_ID=${JWT_APP_ID}"
    echo "export MEDILINK_JITSI_APP_SECRET=${JWT_APP_SECRET}"
    echo "export MEDILINK_JITSI_AUDIENCE=${JWT_ACCEPTED_AUDIENCES}"
    echo "export MEDILINK_JITSI_XMPP_DOMAIN=${XMPP_DOMAIN}"
    echo "export MEDILINK_SINK_TOKEN=${SINK_TOKEN}"
    ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
