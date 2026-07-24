#!/usr/bin/env bash
#
# Orchestrates the actor-mode comparison end to end: for each mode it starts the
# backend fresh (clean journal), waits for health, runs the same bench.mjs
# workload, captures the JSON, stops the backend, then renders RESULTS.md.
#
# Runs the backend from a built distribution so it can be backgrounded and
# stopped cleanly. Requires Node 18+ and a JDK 21 (to build/run the backend).
#
# Usage:
#   ./run-compare.sh                      # default spread workload
#   DURATION_S=20 VUS=60 ./run-compare.sh
#   WORKLOAD=churn VUS=250 SEATS=1 FOCUS_SHOW=<id> ./run-compare.sh   # saturation
#
# Env knobs (forwarded to bench.mjs): DURATION_S, VUS, SEATS, WORKLOAD, FOCUS_SHOW.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "$HERE/../../backend" && pwd)"
PORT="${PORT:-7091}"
HOLD_SECONDS="${HOLD_SECONDS:-5}"
export BASE_URL="http://localhost:${PORT}"
export DURATION_S="${DURATION_S:-15}"
export VUS="${VUS:-50}"
export SEATS="${SEATS:-2}"
export WORKLOAD="${WORKLOAD:-journey}"

BIN="$BACKEND_DIR/build/install/cinetor-backend/bin/cinetor-backend"

echo "==> Building backend distribution"
( cd "$BACKEND_DIR" && ./gradlew --quiet installDist )

# Kill by main class so we never match (and kill) this script itself, and WAIT
# for the process to actually exit. A loaded backend's graceful shutdown can take
# several seconds; because Jetty binds with SO_REUSEADDR, a not-yet-dead previous
# backend can otherwise linger on the port while the next one binds, and the load
# hits the stale (possibly sold-out) instance. So poll until it's really gone,
# escalating to SIGKILL. (pgrep exits 1 when nothing matches; guard it so
# `set -e`/pipefail don't abort.)
stop_backend() {
  local pids
  pids="$(pgrep -f '[C]inetorApp' || true)"
  if [ -z "$pids" ]; then return; fi
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 20); do
    pids="$(pgrep -f '[C]inetorApp' || true)"
    if [ -z "$pids" ]; then return; fi
    sleep 1
  done
  # Still alive after 20s — force it.
  pids="$(pgrep -f '[C]inetorApp' || true)"
  if [ -n "$pids" ]; then kill -9 $pids 2>/dev/null || true; sleep 2; fi
}

trap stop_backend EXIT

mkdir -p "$HERE/results"

for MODE in memory stateful stateful-backpressure; do
  echo "==> Mode: $MODE"
  stop_backend
  rm -rf "$BACKEND_DIR/cajun_persistence"

  # Launch from the backend dir so Cajun's ./cajun_persistence lands there (the
  # same path we clean above); the store is CWD-relative.
  ( cd "$BACKEND_DIR" && ACTOR_MODE="$MODE" HOLD_SECONDS="$HOLD_SECONDS" PORT="$PORT" \
      exec "$BIN" ) > "$HERE/results/$MODE.server.log" 2>&1 &

  # Wait for health.
  for _ in $(seq 1 60); do
    if curl -sf "$BASE_URL/api/health" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  curl -sf "$BASE_URL/api/health" >/dev/null || { echo "backend ($MODE) never came up"; exit 1; }

  # Guard: make sure we're talking to a backend actually running this mode, not a
  # stale one lingering on the port.
  reported="$(curl -sf "$BASE_URL/api/config" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(JSON.parse(d).actorMode||"")}catch{process.stdout.write("")}})')"
  if [ "$reported" != "$MODE" ]; then
    echo "backend on $BASE_URL reports mode '$reported', expected '$MODE' — aborting"; exit 1
  fi

  MODE="$MODE" node "$HERE/bench.mjs" > "$HERE/results/$MODE.json"
  echo "    wrote results/$MODE.json"
  stop_backend
done

echo "==> Rendering report"
node "$HERE/report.mjs" \
  "$HERE/results/memory.json" \
  "$HERE/results/stateful.json" \
  "$HERE/results/stateful-backpressure.json"
