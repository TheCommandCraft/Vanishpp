#!/usr/bin/env bash
# logs-proxy.sh — Tail logs from proxy network services.
# Usage:
#   ./logs-proxy.sh              Follow all services
#   ./logs-proxy.sh velocity     Follow velocity proxy only
#   ./logs-proxy.sh paper1       Follow paper1 server only
#   ./logs-proxy.sh paper2       Follow paper2 server only

set -euo pipefail
cd "$(dirname "$0")"

if [ $# -eq 0 ]; then
  docker compose -f docker-compose-proxy.yml logs -f
else
  docker compose -f docker-compose-proxy.yml logs -f "$@"
fi
