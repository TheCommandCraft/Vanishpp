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
# ViaVersion + ViaBackwards (issue #37): lets the tests join with older client versions.
VIA_VERSION=5.12.1
VIA_SHA256=551265afd892e78bb869162e08b4fdd151c518e245fce60af36b0603ad695d76
VIA_URL="https://cdn.modrinth.com/data/P1OZGk5p/versions/cc7QiCYw/ViaVersion-$VIA_VERSION.jar"
VIAB_SHA256=dd72f7a459cbf1ea7862781dac92ed0ba738aa3cb3b7955e716ba379889d612a
VIAB_URL="https://cdn.modrinth.com/data/NpvuJQoq/versions/GXuG4GNJ/ViaBackwards-$VIA_VERSION.jar"
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
fetch "$VIA_URL" "$VIA_SHA256" ".cache/ViaVersion-$VIA_VERSION.jar"
fetch "$VIAB_URL" "$VIAB_SHA256" ".cache/ViaBackwards-$VIA_VERSION.jar"
[[ -d node_modules ]] || npm ci --ignore-scripts --no-audit --no-fund

build_helper() { # dir jar
  if [[ ! -f $2 || -n $(find "$1/src" "$1/pom.xml" -newer "$2" -print -quit) ]]; then "$MVN" -B -q -f "$1/pom.xml" package; fi
}
HELPER=helper/target/vanishpp-livetest-helper-1.jar
PAPER_HELPER=helper-paper/target/vanishpp-livetest-paper-plugin-1.jar
build_helper helper "$HELPER"
build_helper helper-paper "$PAPER_HELPER"

if $MUTANTS; then
  PLUGIN=$(node mutate.js --mvn "$MVN" | tail -1)
  pass+=(--mutants)
elif [[ -z $PLUGIN ]]; then
  PLUGIN=$(ls ../vanishpp-paper/target/vanishpp-*.jar 2>/dev/null | grep -v '/original-' | head -1 || true)
fi
[[ -f $PLUGIN ]] || { echo "Vanish++ jar not found; build it first (mvn -DskipTests package) or pass its path" >&2; exit 2; }

exec node runner.js --java "$JAVA" --paper ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar" \
  --plugin ".cache/ProtocolLib-$PL_VERSION.jar" --plugin ".cache/ViaVersion-$VIA_VERSION.jar" \
  --plugin ".cache/ViaBackwards-$VIA_VERSION.jar" --plugin "$PAPER_HELPER" --plugin "$PLUGIN" --helper "$HELPER" "${pass[@]}"
