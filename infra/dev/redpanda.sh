#!/usr/bin/env bash
# Redpanda de desenvolvimento (broker compatível com Kafka) para a fila de transcrição (T12).
# Em produção usa-se Kafka; o código do produtor e do consumidor é o mesmo.
#   ./redpanda.sh up       sobe o broker em localhost:9092 e cria os tópicos
#   ./redpanda.sh down     remove o container (os dados são descartáveis)
#   ./redpanda.sh status   mostra o estado e os tópicos
set -euo pipefail

IMAGEM="docker.io/redpandadata/redpanda:v26.2.6"
NOME="medilink-redpanda"
TOPICOS=(medilink.transcricao.jobs medilink.transcricao.resultados)

if command -v podman >/dev/null 2>&1; then RT=podman; elif command -v docker >/dev/null 2>&1; then RT=docker
else echo "Instale podman ou docker." >&2; exit 1; fi

case "${1:-}" in
  up)
    if $RT ps --format '{{.Names}}' | grep -qx "$NOME"; then echo "já está no ar"; else
      $RT rm -f "$NOME" >/dev/null 2>&1 || true
      $RT run -d --name "$NOME" -p 127.0.0.1:9092:9092 "$IMAGEM" redpanda start \
        --mode dev-container --smp 1 --memory 1G --reserve-memory 0M --overprovisioned --node-id 0 --check=false \
        --kafka-addr PLAINTEXT://0.0.0.0:9092 --advertise-kafka-addr PLAINTEXT://localhost:9092 >/dev/null
    fi
    for _ in $(seq 1 60); do $RT exec "$NOME" rpk cluster health >/dev/null 2>&1 && break; sleep 2; done
    for t in "${TOPICOS[@]}"; do $RT exec "$NOME" rpk topic create "$t" -p 1 -r 1 >/dev/null 2>&1 || true; done
    $RT exec "$NOME" rpk topic list ;;
  down)   $RT rm -f "$NOME" ;;
  status) $RT ps --filter "name=$NOME" --format '{{.Names}} {{.Status}}'; $RT exec "$NOME" rpk topic list ;;
  *) sed -n '2,6p' "$0"; exit 1 ;;
esac
