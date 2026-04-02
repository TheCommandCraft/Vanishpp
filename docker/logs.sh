#!/usr/bin/env bash
# logs.sh — Follow server logs.
# Usage:
#   ./logs.sh              Interleaved logs from all servers
#   ./logs.sh folia        Logs from a single server (paper/purpur/folia/spigot/bukkit)

set -euo pipefail
cd "$(dirname "$0")"

if [ $# -gt 0 ]; then
  docker compose logs -f "$@"
else
  docker compose logs -f
fi
