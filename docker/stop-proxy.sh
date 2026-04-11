#!/usr/bin/env bash
# stop-proxy.sh — Stop the proxy network (Velocity + 2 Paper servers).

set -euo pipefail
cd "$(dirname "$0")"

docker compose -f docker-compose-proxy.yml down

echo "Proxy network stopped."
