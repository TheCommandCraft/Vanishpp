#!/usr/bin/env bash
# update.sh — Push a new plugin JAR to all running servers without a full restart.
# The plugin JAR is bind-mounted, so copying it is enough.
# Servers still need a /reload or restart to pick up the new JAR.
#
# Usage:
#   ./update.sh            Copy latest JAR from target/, then /reload all servers via RCON
#   ./update.sh --restart  Copy latest JAR and do a full container restart instead of /reload

set -euo pipefail
cd "$(dirname "$0")"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}✔${NC}  $*"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*"; }

# ── locate and copy JAR ────────────────────────────────────────────────────────

JAR=$(ls -t ../vanishpp-paper/target/vanishpp-*.jar 2>/dev/null | grep -v 'original' | head -1)
if [ -z "$JAR" ]; then
  echo -e "${RED}✖${NC}  No JAR found in target/. Run 'mvn package' first." >&2
  exit 1
fi

mkdir -p plugins
cp "$JAR" plugins/vanishpp.jar
info "Copied $(basename "$JAR") → docker/plugins/vanishpp.jar"

# ── reload or restart ──────────────────────────────────────────────────────────

if [[ "${1:-}" == "--restart" ]]; then
  docker compose restart
  info "All servers restarted."
else
  # Send /vanishreload via RCON to each running server
  declare -A RCON_PORTS=([paper]=25575 [purpur]=25576 [folia]=25577 [spigot]=25578 [bukkit]=25579)
  RCON_PASS="vanishtest"

  for service in paper purpur folia spigot bukkit; do
    port=${RCON_PORTS[$service]}
    if docker compose ps --status running "$service" 2>/dev/null | grep -q "$service"; then
      if docker compose exec "$service" rcon-cli --port "$port" --password "$RCON_PASS" vanishreload 2>/dev/null; then
        info "$service: /vanishreload sent"
      else
        warn "$service: RCON not ready yet — manual /vanishreload needed"
      fi
    else
      warn "$service: not running, skipping"
    fi
  done
fi
