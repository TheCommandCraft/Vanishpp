#!/usr/bin/env bash
# start-proxy.sh — Build Vanishpp and start proxy network (Velocity + 2 Paper servers).
# Usage:
#   ./start-proxy.sh              Start proxy network (uses existing JAR in target/)
#   ./start-proxy.sh --build      Run mvn package first, then start

set -euo pipefail
cd "$(dirname "$0")"

# ── helpers ────────────────────────────────────────────────────────────────────

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}✔${NC}  $*"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*"; }
error() { echo -e "${RED}✖${NC}  $*" >&2; exit 1; }

# ── optional build ─────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--build" ]]; then
  shift
  echo "Building Vanishpp..."

  # Prefer mvn in PATH, fall back to IntelliJ's bundled Maven
  MVN=$(command -v mvn 2>/dev/null || \
        ls "/c/Program Files/JetBrains/"*/plugins/maven/lib/maven3/bin/mvn 2>/dev/null | tail -1 || \
        ls "C:/Program Files/JetBrains/"*/plugins/maven/lib/maven3/bin/mvn.cmd 2>/dev/null | tail -1 || \
        echo "")

  if [ -z "$MVN" ]; then
    error "Maven not found. Install it or run 'mvn package' manually first."
  fi

  JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/ms-21.0.8}"
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  "$MVN" -f ../pom.xml package -DskipTests -q
  info "Build complete."
fi

# ── locate JAR ─────────────────────────────────────────────────────────────────

JAR=$(ls ../vanishpp-paper/target/vanishpp-*.jar 2>/dev/null | grep -v 'original' | sort -V | tail -1)
if [ -z "$JAR" ]; then
  error "No JAR found in vanishpp-paper/target/. Run './start-proxy.sh --build' or 'mvn package' first."
fi

mkdir -p plugins
cp "$JAR" plugins/vanishpp.jar
info "Copied $(basename "$JAR") → docker/plugins/vanishpp.jar"

# ── start proxy network ────────────────────────────────────────────────────────

docker compose -f docker-compose-proxy.yml up -d

echo ""
echo -e "${GREEN}Proxy network starting:${NC}"
echo "  Velocity Proxy → localhost:25565"
echo "  Paper 1        → localhost:25575 (RCON)"
echo "  Paper 2        → localhost:25576 (RCON)"
echo ""
echo "PostgreSQL: vpp_proxy_postgres"
echo "RCON password: vanishtest"
echo ""
echo "Commands:"
echo "  ./logs-proxy.sh [service]       Follow logs (velocity|paper1|paper2|postgres)"
echo "  ./update-proxy.sh               Push new JAR to both paper servers"
echo "  ./update-proxy.sh --restart     Restart services after JAR update"
echo "  ./stop-proxy.sh                 Stop proxy network"
echo ""
