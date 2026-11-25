#!/usr/bin/env bash
# Live proxy tests: a Velocity proxy with two Paper backends (lobby, pvp), both Vanish++ jars, and bot players.
#   live-tests/proxy.sh [path/to/vanishpp.jar path/to/vanishpp-velocity.jar] [--verbose]
# Needs the same tools as run.sh (Java 21+, plus Java 25 for Velocity via JAVA25=..., Node 20+). Everything binds to 127.0.0.1.
set -euo pipefail

MC=1.21.11 # bots join natively, so no ViaVersion is involved
VELOCITY_VERSION=4.3.1-45
VELOCITY_SHA256=0844bcabdcdb4f5b875906fd4c3365bc1bb76918e42bc595ed88ad195258dd75
VELOCITY_URL="https://fill-data.papermc.io/v1/objects/$VELOCITY_SHA256/velocity-$VELOCITY_VERSION.jar"
JAVA=${JAVA:-java}
JAVA25=${JAVA25:-$JAVA} # Velocity 4 needs Java 25

PAPER_JAR="" VELOCITY_PLUGIN="" pass=()
for a in "$@"; do
  case $a in
    --verbose) pass+=(--verbose) ;;
    *) if [[ -z $PAPER_JAR ]]; then PAPER_JAR=$(realpath "$a"); else VELOCITY_PLUGIN=$(realpath "$a"); fi ;;
  esac
done
cd "$(dirname "$0")"
# The project version from the root pom: target/ can hold jars of older builds, which must never be picked.
VERSION=$(sed -n 's:^    <version>\(.*\)</version>$:\1:p' ../pom.xml | head -1)
[[ -n $PAPER_JAR ]] || PAPER_JAR=../vanishpp-paper/target/vanishpp-$VERSION.jar
[[ -n $VELOCITY_PLUGIN ]] || VELOCITY_PLUGIN=../vanishpp-velocity/target/vanishpp-velocity-$VERSION.jar
[[ -f $PAPER_JAR && -f $VELOCITY_PLUGIN ]] || { echo "Vanish++ $VERSION jars not found; build them first (mvn -DskipTests package) or pass both paths" >&2; exit 2; }
echo "Testing $(basename "$PAPER_JAR") and $(basename "$VELOCITY_PLUGIN")"

read -r PAPER_VERSION PAPER_BUILD PAPER_SHA256 _ BOT_VERSION < <(awk -v v="$MC" '$1 == v' versions.tsv)
fetch() { # url sha256 file
  if [[ -f $3 ]] && echo "$2  $3" | sha256sum -c --quiet - 2>/dev/null; then return; fi
  echo "Downloading $(basename "$3")"
  curl -fsSL --retry 4 --retry-all-errors --retry-delay 5 -A "TheCommandCraft/Vanishpp live-tests" -o "$3.part" "$1"
  echo "$2  $3.part" | sha256sum -c --quiet - || { echo "Checksum mismatch for $1" >&2; rm -f "$3.part"; exit 2; }
  mv "$3.part" "$3"
}
mkdir -p .cache .work
fetch "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar" "$PAPER_SHA256" ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
fetch "$VELOCITY_URL" "$VELOCITY_SHA256" ".cache/velocity-$VELOCITY_VERSION.jar"
[[ -d node_modules ]] || npm ci --ignore-scripts --no-audit --no-fund

exec node proxy.js --java "$JAVA" --bot-version "$BOT_VERSION" --paper ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar" \
  --velocity ".cache/velocity-$VELOCITY_VERSION.jar" --velocity-java "$JAVA25" --plugin "$PAPER_JAR" --velocity-plugin "$VELOCITY_PLUGIN" "${pass[@]}"
