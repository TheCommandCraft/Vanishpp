#!/usr/bin/env bash
# update-proxy.sh — Update Vanishpp JAR in proxy network and reload servers.
# Usage:
#   ./update-proxy.sh              Build, copy JAR, and reload servers
#   ./update-proxy.sh --restart    Build, copy JAR, and restart services (full reload)

set -euo pipefail
cd "$(dirname "$0")"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}✔${NC}  $*"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*"; }
error() { echo -e "${RED}✖${NC}  $*" >&2; exit 1; }

# ── build ──────────────────────────────────────────────────────────────────────

echo "Building Vanishpp..."

MVN=$(command -v mvn 2>/dev/null || \
      ls "/c/Program Files/JetBrains/"*/plugins/maven/lib/maven3/bin/mvn 2>/dev/null | tail -1 || \
      ls "C:/Program Files/JetBrains/"*/plugins/maven/lib/maven3/bin/mvn.cmd 2>/dev/null | tail -1 || \
      echo "")

if [ -z "$MVN" ]; then
  error "Maven not found."
fi

JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/ms-21.0.8}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

"$MVN" -f ../pom.xml package -DskipTests -q
info "Build complete."

# ── locate JAR ─────────────────────────────────────────────────────────────────

JAR=$(ls ../target/vanishpp-*.jar 2>/dev/null | grep -v 'original' | head -1)
if [ -z "$JAR" ]; then
  error "No JAR found in target/."
fi

# ── copy JAR ───────────────────────────────────────────────────────────────────

mkdir -p plugins
cp "$JAR" plugins/vanishpp.jar
info "Copied $(basename "$JAR") → docker/plugins/vanishpp.jar"

# ── reload or restart ──────────────────────────────────────────────────────────

if [[ "${1:-}" == "--restart" ]]; then
  info "Restarting paper1..."
  docker compose -f docker-compose-proxy.yml restart paper1
  info "Restarting paper2..."
  docker compose -f docker-compose-proxy.yml restart paper2
  info "Services restarted."
else
  info "Sending /vanishreload to paper1..."
  docker exec vpp_proxy_paper1 rcon-cli --password vanishtest /vanishreload
  info "Sending /vanishreload to paper2..."
  docker exec vpp_proxy_paper2 rcon-cli --password vanishtest /vanishreload
  info "Servers reloaded."
fi

echo ""
info "Update complete."
