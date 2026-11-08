#!/usr/bin/env bash
# Live bot tests for Vanish++. One Paper server and one pool of bots serve the whole run.
#   live-tests/run.sh [path/to/vanishpp.jar] [--only <text>] [--verbose]   test the built plugin
#   live-tests/run.sh --mutants [--only <text>]                            test the tests (see README)
# Needs Java 21+ (JAVA=/path/to/java), Maven (MVN=/path/to/mvn) and Node 20+. Downloads are checksum-pinned.
set -euo pipefail
cd "$(dirname "$0")"

PAPER_VERSION=1.21.11
PAPER_BUILD=132
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PAPER_URL="https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
PL_VERSION=5.4.0
PL_SHA256=ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf
PL_URL="https://github.com/dmulloy2/ProtocolLib/releases/download/$PL_VERSION/ProtocolLib.jar"
JAVA=${JAVA:-java}
MVN=${MVN:-mvn}

MUTANTS=false
PLUGIN=""
pass=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --mutants) MUTANTS=true ;;
    --only) pass+=(--only "$2"); shift ;;
    --verbose) pass+=(--verbose) ;;
    *) PLUGIN=$1 ;;
  esac
  shift
done

fetch() { # url sha256 file
  if [[ -f $3 ]] && echo "$2  $3" | sha256sum -c --quiet - 2>/dev/null; then return; fi
  echo "Downloading $(basename "$3")"
  curl -fsSL --retry 4 --retry-all-errors --retry-delay 5 -A "TheCommandCraft/Vanishpp live-tests" -o "$3.part" "$1"
  echo "$2  $3.part" | sha256sum -c --quiet - || { echo "Checksum mismatch for $1" >&2; rm -f "$3.part"; exit 2; }
  mv "$3.part" "$3"
}
mkdir -p .cache .work
fetch "$PAPER_URL" "$PAPER_SHA256" ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
fetch "$PL_URL" "$PL_SHA256" ".cache/ProtocolLib-$PL_VERSION.jar"
[[ -d node_modules ]] || npm ci --ignore-scripts --no-audit --no-fund

HELPER=helper/target/vanishpp-livetest-helper-1.jar
if [[ ! -f $HELPER || -n $(find helper/src helper/pom.xml -newer "$HELPER" -print -quit) ]]; then
  "$MVN" -B -q -f helper/pom.xml package
fi

if $MUTANTS; then
  PLUGIN=$(node mutate.js --mvn "$MVN" | tail -1)
  pass+=(--mutants)
elif [[ -z $PLUGIN ]]; then
  PLUGIN=$(ls ../vanishpp-paper/target/vanishpp-*.jar 2>/dev/null | grep -v '/original-' | head -1 || true)
fi
[[ -f $PLUGIN ]] || { echo "Vanish++ jar not found; build it first (mvn -DskipTests package) or pass its path" >&2; exit 2; }

exec node runner.js --java "$JAVA" --paper ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar" \
  --plugin ".cache/ProtocolLib-$PL_VERSION.jar" --plugin "$PLUGIN" --helper "$HELPER" "${pass[@]}"
