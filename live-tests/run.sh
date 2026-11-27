#!/usr/bin/env bash
# Live bot tests for Vanish++. One Paper server and one pool of bots serve the whole run.
#   live-tests/run.sh [path/to/vanishpp.jar] [--only <text>] [--verbose]   test the built plugin
#   live-tests/run.sh --mutants [--only <text>] [--shard i/n]              test the tests (see README)
#   add --mc <version> for another Paper version (versions.tsv), or --all-versions for every one in turn
# Needs Java 21+ (JAVA=/path/to/java; JAVA25=... for Minecraft 26.x), Maven (MVN=/path/to/mvn) and Node 20+. Downloads are checksum-pinned.
set -euo pipefail
CALLER=$PWD # a jar path given on the command line is relative to where run.sh was called from
cd "$(dirname "$0")"

# Paper versions: see versions.tsv. --mc <version> picks one (default below), --all-versions runs them all in turn.
DEFAULT_MC=1.21.11
PL_VERSION=5.4.0
PL_SHA256=ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf
PL_URL="https://github.com/dmulloy2/ProtocolLib/releases/download/$PL_VERSION/ProtocolLib.jar"
# ViaVersion + ViaBackwards (issue #37): lets the tests join with older client versions.
VIA_VERSION=5.12.1
VIA_SHA256=551265afd892e78bb869162e08b4fdd151c518e245fce60af36b0603ad695d76
VIA_URL="https://cdn.modrinth.com/data/P1OZGk5p/versions/cc7QiCYw/ViaVersion-$VIA_VERSION.jar"
VIAB_SHA256=dd72f7a459cbf1ea7862781dac92ed0ba738aa3cb3b7955e716ba379889d612a
VIAB_URL="https://cdn.modrinth.com/data/NpvuJQoq/versions/GXuG4GNJ/ViaBackwards-$VIA_VERSION.jar"
# TAB (NEZNAMY): only for the scenario that checks the combination (12-tab-plugin); the server starts without it.
TAB_VERSION=6.2.1
TAB_SHA256=0b6a21af6fd3b113c05821daaed00ff92234a84ca94b29b3aec0ef1bd313b8a9
TAB_URL="https://cdn.modrinth.com/data/gG7VFbG0/versions/Bu3K2CAM/TAB%20v6.2.1.jar"
JAVA=${JAVA:-java}
JAVA25=${JAVA25:-$JAVA} # Minecraft 26.x needs Java 25
MVN=${MVN:-mvn}

MUTANTS=false
PLUGIN=""
MC=$DEFAULT_MC
ALL=false
pass=()
orig_args=("$@")
while [[ $# -gt 0 ]]; do
  case $1 in
    --mutants) MUTANTS=true ;;
    --mc) MC=$2; shift ;;
    --all-versions) ALL=true ;;
    --only) pass+=(--only "$2"); shift ;;
    --shard) pass+=(--shard "$2"); shift ;;
    --port) pass+=(--port "$2"); shift ;;
    --verbose) pass+=(--verbose) ;;
    *) PLUGIN=$(cd "$CALLER" && realpath "$1") ;;
  esac
  shift
done

if $ALL; then # every version in turn, one server at a time; stops at the first failing version
  for v in $(awk '!/^#/ && NF {print $1}' versions.tsv); do
    rest=(); skip=false
    for a in "${orig_args[@]}"; do
      if $skip; then skip=false; continue; fi
      case $a in --all-versions) ;; --mc) skip=true ;; *) rest+=("$a") ;; esac
    done
    echo "=== Paper $v ==="
    "$PWD/run.sh" --mc "$v" "${rest[@]}" || { echo "=== Paper $v failed ==="; exit 1; }
  done
  exit 0
fi
read -r PAPER_VERSION PAPER_BUILD PAPER_SHA256 JAVA_MAJOR BOT_VERSION < <(awk -v v="$MC" '$1 == v' versions.tsv)
[[ -n ${PAPER_VERSION:-} ]] || { echo "Unknown Minecraft version $MC (see versions.tsv)" >&2; exit 2; }
PAPER_URL="https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/paper-$PAPER_VERSION-$PAPER_BUILD.jar"
[[ $JAVA_MAJOR == 25 ]] && JAVA=$JAVA25

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
fetch "$TAB_URL" "$TAB_SHA256" ".cache/TAB-$TAB_VERSION.jar"
[[ -d node_modules ]] || npm ci --ignore-scripts --no-audit --no-fund

build_helper() { # dir jar
  if [[ ! -f $2 || -n $(find "$1/src" "$1/pom.xml" -newer "$2" -print -quit) ]]; then "$MVN" -B -q -f "$1/pom.xml" package; fi
}
HELPER=helper/target/vanishpp-livetest-helper-1.jar
PAPER_HELPER=helper-paper/target/vanishpp-livetest-paper-plugin-1.jar
build_helper helper "$HELPER"
build_helper helper-paper "$PAPER_HELPER"
build_helper helper-conflict helper-conflict/target/vanishpp-livetest-conflict-1.jar # only loaded by 15-command-conflict

if $MUTANTS; then
  PLUGIN=$(node mutate.js --mvn "$MVN" | tail -1)
  pass+=(--mutants)
elif [[ -z $PLUGIN ]]; then
  # The project version from the root pom: target/ can hold jars of older builds, which must never be picked.
  PLUGIN=../vanishpp-paper/target/vanishpp-$(sed -n 's:^    <version>\(.*\)</version>$:\1:p' ../pom.xml | head -1).jar
fi
[[ -f $PLUGIN ]] || { echo "Vanish++ jar $PLUGIN not found; build it first (mvn -DskipTests package) or pass its path" >&2; exit 2; }
echo "Testing $(basename "$PLUGIN")"

exec node runner.js --java "$JAVA" --mc "$PAPER_VERSION" --bot-version "$BOT_VERSION" --paper ".cache/paper-$PAPER_VERSION-$PAPER_BUILD.jar" \
  --plugin ".cache/ProtocolLib-$PL_VERSION.jar" --plugin ".cache/ViaVersion-$VIA_VERSION.jar" \
  --plugin ".cache/ViaBackwards-$VIA_VERSION.jar" --plugin "$PAPER_HELPER" --plugin "$PLUGIN" --helper "$HELPER" "${pass[@]}"
