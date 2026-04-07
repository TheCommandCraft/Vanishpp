#!/usr/bin/env bash
# test-dbsync.sh — Cross-server vanish DB sync + Folia compatibility integration test
#
# Automated phases (no player needed):
#   0  Preflight — containers running, RCON reachable
#   1  Configure shared PostgreSQL on Paper, Purpur, and Folia
#   2  DB schema sanity — all tables and schema version present
#   3  Synthetic UUID — insert/delete via psql, verify servers never corrupt DB
#   5  Stale offline entry — verify servers don't write stale entries back to DB
#   6  Folia entity-scheduler — verify no UnsupportedOperationException in Folia logs
#
# Semi-automated phase (one /vanish command per server):
#   4  Live cross-server sync — Paper → Purpur → Folia with real player UUID
#
# Requirements: ./start.sh paper folia
# Usage: bash test-dbsync.sh [--player <name>]   default: TheCommandCraft

set -uo pipefail  # -e omitted: test failures should not abort the script
cd "$(dirname "$0")"

# ── config ─────────────────────────────────────────────────────────────────────
PLAYER="${2:-TheCommandCraft}"
RCON_PASS="vanishtest"
PG_CONTAINER="vpp_postgres"
PG_USER="vanishpp"
PG_DB="vanishpp"
PAPER_CONTAINER="vpp_paper"
PURPUR_CONTAINER="vpp_purpur"
FOLIA_CONTAINER="vpp_folia"

# ── helpers ─────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
ok()    { echo -e "${GREEN}✔${NC}  $*"; }
fail()  { echo -e "${RED}✖${NC}  $*"; FAILURES=$((FAILURES+1)); }
info()  { echo -e "${CYAN}▶${NC}  $*"; }
warn()  { echo -e "${YELLOW}⚠${NC}  $*"; }
header(){ echo -e "\n${BOLD}$*${NC}"; echo -e "${BOLD}$(echo "$*" | sed 's/./─/g')${NC}"; }
FAILURES=0

# All RCON calls are routed through the Paper container on the Docker bridge network.
# Each server listens on its own internal RCON port (set by RCON_PORT env var).
PAPER_RCON_PORT=25575
FOLIA_RCON_PORT=25577

rcon() {
    local host=$1 port=$2; shift 2
    docker exec "$PAPER_CONTAINER" rcon-cli \
        --host "$host" --port "$port" --password "$RCON_PASS" "$@" 2>/dev/null \
        | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}
rcon_paper() { rcon "localhost" "$PAPER_RCON_PORT" "$@"; }
rcon_folia()  { rcon "folia"   "$FOLIA_RCON_PORT"  "$@"; }

# psql: run a query, return trimmed single-value result
psql_q() {
    docker exec "$PG_CONTAINER" \
        psql -U "$PG_USER" -d "$PG_DB" -t -A -c "$1" 2>/dev/null \
        | tr -d '\r\n '
}

# wait for RCON to respond; caller decides whether to exit or continue on timeout
wait_rcon() {
    local host=$1 port=$2 label=$3
    info "Waiting for $label RCON..."
    for i in $(seq 1 30); do
        if rcon "$host" "$port" "list" &>/dev/null; then
            ok "$label is ready"
            return 0
        fi
        sleep 2
    done
    fail "$label did not respond within 60s"
    return 1
}

# poll psql until COUNT(*) matches expected; increments FAILURES on timeout
wait_psql() {
    local query=$1 expected=$2 label=$3 timeout=${4:-15}
    for i in $(seq 1 "$timeout"); do
        if [ "$(psql_q "$query")" = "$expected" ]; then
            ok "$label"
            return 0
        fi
        sleep 1
    done
    fail "$label (got '$(psql_q "$query")' — wanted '$expected')"
}

# patch a container's Vanish++ config to point at the shared PostgreSQL instance
patch_pg_config() {
    local container=$1
    docker exec "$container" bash -c '
        CFG="/data/plugins/Vanishpp/config.yml"
        [ -f "$CFG" ] || { echo "config.yml not found in $CFG"; exit 1; }
        sed -i \
            -e "s|  type: \"YAML\"|  type: \"POSTGRESQL\"|" \
            -e "/^  mysql:/,/^  redis:/{s|    host: \"localhost\"|    host: \"postgres\"|}" \
            -e "/^  mysql:/,/^  redis:/{s|    port: 3306|    port: 5432|}" \
            -e "/^  mysql:/,/^  redis:/{s|    username: \"root\"|    username: \"vanishpp\"|}" \
            -e "/^  mysql:/,/^  redis:/{s|    password: \"\"|    password: \"vanishtest\"|}" \
            "$CFG"
    ' 2>/dev/null
}

# ── Phase 0: preflight ────────────────────────────────────────────────────────
header "Phase 0 — Preflight"

for c in "$PG_CONTAINER" "$PAPER_CONTAINER" "$FOLIA_CONTAINER"; do
    if docker ps --format '{{.Names}}' | grep -q "^${c}$"; then
        ok "$c is running"
    else
        fail "$c is not running"
        echo "  Start with: ./start.sh paper folia"
        exit 1
    fi
done

wait_rcon "localhost" "$PAPER_RCON_PORT" "Paper" || exit 1
wait_rcon "folia"     "$FOLIA_RCON_PORT"  "Folia" || exit 1

# ── Phase 1: configure shared PostgreSQL ─────────────────────────────────────
header "Phase 1 — Configure shared PostgreSQL on Paper and Folia"

for CONTAINER in "$PAPER_CONTAINER" "$FOLIA_CONTAINER"; do
    patch_pg_config "$CONTAINER"
    ok "$CONTAINER config patched"
done

info "Reloading Vanish++ on Paper and Folia..."
rcon_paper "vanishreload" >/dev/null
rcon_folia "vanishreload" >/dev/null
sleep 4  # Folia reload can be slightly slower
ok "Both servers reloaded"

# ── Phase 2: DB schema sanity ────────────────────────────────────────────────
header "Phase 2 — DB schema sanity"

for table in vpp_vanished vpp_rules vpp_levels vpp_acknowledgements vpp_schema_version; do
    count=$(psql_q "SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_name = '$table' AND table_schema = 'public';")
    if [ "$count" = "1" ]; then
        ok "Table $table exists"
    else
        fail "Table $table is missing — did the plugin connect to PostgreSQL?"
    fi
done

schema=$(psql_q "SELECT version FROM vpp_schema_version LIMIT 1;")
ok "Schema version: $schema"

# ── Phase 3: automated DB state (no player needed) ───────────────────────────
header "Phase 3 — Automated DB state (synthetic UUID)"

TEST_UUID="ffffffff-dbsy-4c00-0000-000000000099"

psql_q "DELETE FROM vpp_vanished WHERE uuid = '$TEST_UUID';" >/dev/null
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$TEST_UUID';" \
          "0" "Synthetic UUID absent initially"

# Simulate another server writing vanish state directly to DB
psql_q "INSERT INTO vpp_vanished (uuid) VALUES ('$TEST_UUID') ON CONFLICT DO NOTHING;" >/dev/null
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$TEST_UUID';" \
          "1" "Synthetic UUID inserted via psql"

# Reload both servers — neither should clear the row (offline player must stay vanished in DB)
rcon_paper "vanishreload" >/dev/null
rcon_folia "vanishreload" >/dev/null
sleep 3
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$TEST_UUID';" \
          "1" "Row survives reload on both servers (offline vanish preserved)"

# Simulate remote unvanish
psql_q "DELETE FROM vpp_vanished WHERE uuid = '$TEST_UUID';" >/dev/null
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$TEST_UUID';" \
          "0" "Synthetic UUID deleted from DB (remote unvanish)"

# Reload both servers — neither should resurrect the deleted row
rcon_paper "vanishreload" >/dev/null
rcon_folia "vanishreload" >/dev/null
sleep 3
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$TEST_UUID';" \
          "0" "Deleted row not resurrected by either server after reload"

# ── Phase 4: live player cross-server sync ────────────────────────────────────
# Clear any stale vanish rows left from prior runs so Phase 4 gets a clean slate
psql_q "DELETE FROM vpp_vanished;" >/dev/null

header "Phase 4 — Live player cross-server sync"
echo
echo -e "  ${BOLD}Manual steps required.${NC} Connect your client to each server in turn."
echo    "  Paper → localhost:25565   Folia → localhost:25567"
echo    "  Player: $PLAYER"
echo

# ── 4a: vanish on Paper ───────────────────────────────────────────────────────
echo -e "  ${BOLD}[4a]${NC} Connect to ${GREEN}Paper (25565)${NC} as $PLAYER."
printf  "       Press Enter when connected → "
read -r

echo    "       Type: /vanish"
printf  "       Press Enter after /vanish → "
read -r

info "Polling DB for vanish row (up to 10s)..."
PLAYER_UUID=""
for i in $(seq 1 10); do
    row=$(docker exec "$PG_CONTAINER" \
        psql -U "$PG_USER" -d "$PG_DB" -t -A \
        -c "SELECT uuid FROM vpp_vanished ORDER BY created_at DESC LIMIT 1;" 2>/dev/null \
        | tr -d '\r\n ')
    if [ -n "$row" ] && [ "$row" != "$TEST_UUID" ]; then
        PLAYER_UUID="$row"
        break
    fi
    sleep 1
done

if [ -n "$PLAYER_UUID" ]; then
    ok "Paper wrote vanish to DB: $PLAYER_UUID"
else
    fail "No vanish row found in DB within 10s — are you OP on Paper?"
    PLAYER_UUID="unknown"
fi

# ── 4b: join Folia while still vanished ──────────────────────────────────────
echo
echo -e "  ${BOLD}[4b]${NC} Without unvanishing, switch to ${GREEN}Folia (25567)${NC}."
printf  "       Press Enter once connected to Folia → "
read -r
sleep 2

if [ "$PLAYER_UUID" != "unknown" ]; then
    wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$PLAYER_UUID';" \
              "1" "Vanish row intact after joining Folia (reconciliation kept state)"
fi

folia_list=$(rcon_folia "vlist" 2>/dev/null || echo "")
if echo "$folia_list" | grep -qi "$PLAYER"; then
    ok "Folia /vlist shows $PLAYER as vanished"
else
    warn "Folia /vlist: $folia_list"
    warn "Player not listed — confirm you are invisible to non-staff on Folia"
fi

# ── 4c: unvanish on Folia ────────────────────────────────────────────────────
echo
echo    "       Type: /vanish  (unvanish on Folia)"
printf  "       Press Enter after unvanished → "
read -r

if [ "$PLAYER_UUID" != "unknown" ]; then
    wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$PLAYER_UUID';" \
              "0" "Vanish row removed from DB after unvanish on Folia"
fi

# ── Phase 5: stale offline entry cleanup ─────────────────────────────────────
header "Phase 5 — Stale offline entry cleanup (60s periodic task)"

STALE_UUID="ffffffff-dead-4c00-0000-000000000001"
psql_q "DELETE FROM vpp_vanished WHERE uuid = '$STALE_UUID';" >/dev/null
psql_q "INSERT INTO vpp_vanished (uuid) VALUES ('$STALE_UUID') ON CONFLICT DO NOTHING;" >/dev/null

# Load stale UUID into both server memories via reload
rcon_paper "vanishreload" >/dev/null
rcon_folia "vanishreload" >/dev/null
sleep 3

# Delete from DB — simulates remote unvanish while player is offline everywhere
psql_q "DELETE FROM vpp_vanished WHERE uuid = '$STALE_UUID';" >/dev/null
ok "Stale UUID deleted from DB (player offline on all servers)"

# Verify no server writes it back (stale in-memory entries must not re-persist to DB)
sleep 5
wait_psql "SELECT COUNT(*) FROM vpp_vanished WHERE uuid = '$STALE_UUID';" \
          "0" "No server wrote stale entry back to DB" 30

# ── Phase 6: Folia entity-scheduler (PR #1 regression check) ─────────────────
header "Phase 6 — Folia entity-scheduler: no UnsupportedOperationException"
echo
echo    "  This phase checks that night vision potion effects are applied via"
echo    "  the entity scheduler on Folia (fix from PR #1 by SimplyRan)."
echo    "  An UnsupportedOperationException would appear in Folia's log if the"
echo    "  fix were missing and vanish effects were applied from the global scheduler."
echo

# Mark the log timestamp so we only scan new output
LOG_MARK=$(date -u +"%Y-%m-%dT%H:%M:%S")

# Re-apply vanish state reconciliation by forcing a reload while the player is
# vanished (if they are). The reload triggers applyVanishEffects via the global
# scheduler path for any vanished UUID — this is exactly the path PR #1 fixes.
rcon_folia "vanishreload" >/dev/null
sleep 3

# Scan Folia logs since the mark for entity-thread violations
EXCEPTION_LINES=$(docker logs "$FOLIA_CONTAINER" --since "$LOG_MARK" 2>&1 \
    | grep -i "UnsupportedOperationException\|Cannot.*entity.*thread\|entity.*wrong.*thread" \
    || true)

if [ -z "$EXCEPTION_LINES" ]; then
    ok "No entity-thread exceptions in Folia log after reload"
else
    fail "Entity-thread exceptions found in Folia log:"
    echo "$EXCEPTION_LINES" | while IFS= read -r line; do
        echo "    $line"
    done
fi

# Also check Folia logs for any vanishpp SEVERE errors in this window
SEVERE_LINES=$(docker logs "$FOLIA_CONTAINER" --since "$LOG_MARK" 2>&1 \
    | grep -i "\[vanish\|vanishpp\].*SEVERE\|vanishpp.*error\|SEVERE.*vanish" \
    || true)

if [ -z "$SEVERE_LINES" ]; then
    ok "No Vanish++ SEVERE errors in Folia log after reload"
else
    warn "Vanish++ SEVERE entries found in Folia log:"
    echo "$SEVERE_LINES" | while IFS= read -r line; do
        echo "    $line"
    done
fi

# ── Summary ───────────────────────────────────────────────────────────────────
header "Summary"
if [ "$FAILURES" -eq 0 ]; then
    echo -e "${GREEN}${BOLD}All tests passed.${NC}"
else
    echo -e "${RED}${BOLD}$FAILURES test(s) failed.${NC} Check output above."
fi
echo
