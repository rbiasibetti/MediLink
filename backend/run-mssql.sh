#!/usr/bin/env bash
# Roda o backend contra o SQL Server local (credenciais em config/local.env).
#   ./run-mssql.sh test [args do mvn]   testes contra o banco medilink_test (ex.: -Dtest=ConsultaAceiteApiTest)
#   ./run-mssql.sh dev    aplicação (perfil dev) contra o banco medilink_dev, porta 8090
set -euo pipefail
cd "$(dirname "$0")"
[ -f config/local.env ] || { echo "Crie config/local.env a partir de config/local.env.example" >&2; exit 1; }
. config/local.env
case "${1:-}" in
  test)
    MEDILINK_DB_URL="$MEDILINK_TEST_DB_URL" ./mvnw -B test "${@:2}" ;;
  dev)
    : "${MEDILINK_CPF_HMAC_KEY:=$(openssl rand -hex 16)}"
    : "${MEDILINK_JITSI_APP_SECRET:?exporte as variáveis do Jitsi: eval \"\$(../infra/jitsi/dev.sh env)\"}"
    : "${MEDILINK_SINK_TOKEN:?exporte também o segredo do audio sink (vem do mesmo eval do dev.sh env)}"
    export MEDILINK_CPF_HMAC_KEY MEDILINK_JITSI_APP_SECRET MEDILINK_SINK_TOKEN
    SPRING_PROFILES_ACTIVE=dev ./mvnw -B spring-boot:run -Dspring-boot.run.arguments=--server.port=8090 ;;
  *) sed -n '2,4p' "$0"; exit 1 ;;
esac
