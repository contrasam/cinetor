#!/usr/bin/env bash
# Convenience runner for the Cinetor backend load + correctness suite.
#
# Usage:
#   ./run.sh browse        # read-heavy catalogue browsing
#   ./run.sh journey       # write-heavy booking journey + inline invariants
#   ./run.sh race          # same-seat stampede (correctness centrepiece)
#   ./run.sh ceiling       # single-actor saturation probe
#   ./run.sh sse           # SSE fan-out + broadcast correctness (Node)
#   ./run.sh all           # browse -> journey -> race -> sse (skips ceiling)
#
# Env:
#   BASE_URL   backend base url (default http://localhost:7070)
#   K6         path to the k6 binary (default: `k6` on PATH)
#   RACE_VUS, SHOW_ID, SUBSCRIBERS  passed through to the relevant scenario.
set -euo pipefail

cd "$(dirname "$0")"
K6="${K6:-k6}"
export BASE_URL="${BASE_URL:-http://localhost:7070}"

run_k6()   { "$K6" run "scenarios/$1"; }
run_sse()  { node sse/sse-fanout.mjs; }

case "${1:-}" in
  browse)  run_k6 browse.js ;;
  journey) run_k6 booking-journey.js ;;
  race)    run_k6 race-same-seat.js ;;
  ceiling) run_k6 single-show-ceiling.js ;;
  sse)     run_sse ;;
  all)
    run_k6 browse.js
    run_k6 booking-journey.js
    run_k6 race-same-seat.js
    run_sse
    ;;
  *)
    echo "usage: $0 {browse|journey|race|ceiling|sse|all}" >&2
    exit 1
    ;;
esac
