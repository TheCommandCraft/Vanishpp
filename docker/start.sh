#!/usr/bin/env bash
# start.sh — Build Vanishpp and start all test servers.
# Usage:
#   ./start.sh              Start all servers (uses existing JAR in target/)
#   ./start.sh --build      Run mvn package first, then start
#   ./start.sh paper folia  Start only specific services

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

JAR=$(ls ../target/vanishpp-*.jar 2>/dev/null | grep -v 'original' | head -1)
if [ -z "$JAR" ]; then
  error "No JAR found in target/. Run './start.sh --build' or 'mvn package' first."
fi

mkdir -p plugins
cp "$JAR" plugins/vanishpp.jar
info "Copied $(basename "$JAR") → docker/plugins/vanishpp.jar"

# ── start servers ──────────────────────────────────────────────────────────────

if [ $# -gt 0 ]; then
  # Start only the services passed as arguments
  docker compose up -d "$@"
else
  docker compose up -d
fi

echo ""
echo -e "${GREEN}Servers starting:${NC}"
echo "  Paper   → localhost:25565   RCON: 25575"
echo "  Purpur  → localhost:25566   RCON: 25576"
echo "  Folia   → localhost:25567   RCON: 25577"
echo "  Spigot  → localhost:25568   RCON: 25578  ⚠ BuildTools: 10-15 min on first launch"
echo "  Bukkit  → localhost:25569   RCON: 25579  ⚠ BuildTools: 10-15 min on first launch"
echo ""
echo "RCON password: vanishtest"
echo ""
echo "Commands:"
echo "  ./logs.sh [service]    Follow logs (e.g. ./logs.sh folia)"
echo "  ./update.sh            Push a new JAR to all running servers"
echo "  ./stop.sh              Stop all servers"
