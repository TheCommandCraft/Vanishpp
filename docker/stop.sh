#!/usr/bin/env bash
# stop.sh — Stop all test servers.
# Usage:
#   ./stop.sh              Stop all servers (containers stay, data preserved)
#   ./stop.sh --clean      Stop all servers and remove their data volumes

set -euo pipefail
cd "$(dirname "$0")"

if [[ "${1:-}" == "--clean" ]]; then
  echo "Stopping servers and removing all data volumes..."
  docker compose down -v
  echo "✔  All servers stopped and data wiped."
else
  docker compose down
  echo "✔  All servers stopped. Data preserved."
  echo "    Run './stop.sh --clean' to also wipe world/plugin data."
fi
