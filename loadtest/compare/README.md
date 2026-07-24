# Actor-mode comparison

The next phase of Cinetor makes the seat actors' **persistence model** a runtime
choice and compares three flavours of the same `ShowActor`:

| Mode | Actor | Durability | Flow control |
|---|---|---|---|
| `memory` | `ShowActor` (in-memory) | none — restart loses all state | none |
| `stateful` | `StatefulShowActor` + Cajun journal/snapshots | seat state survives a restart | none |
| `stateful-backpressure` | `StatefulShowActor` + persistence + bounded mailbox | survives a restart | mailbox backpressure (BLOCK) |

The booking API and its correctness (one actor per show, no double-booking) are
identical across all three — only how each actor is spawned changes. That is
what lets this harness swap modes without touching the app.

## Pick a mode when starting the backend

```bash
cd backend
ACTOR_MODE=stateful HOLD_SECONDS=15 ./gradlew run
# or: ./gradlew run -PactorMode=stateful-backpressure
```

`GET /api/config` reports the active mode; the startup log prints it too. Default
is `memory` (the demo's original behaviour). The stateful modes write their
journal + snapshots to `./cajun_persistence` (relative to the backend's working
directory) and **eagerly warm every show actor at startup** so the first real
request per show doesn't pay a cold-start init.

## Run the comparison

```bash
cd loadtest/compare
./run-compare.sh                 # spread workload, all three modes -> RESULTS.md
DURATION_S=30 VUS=80 ./run-compare.sh
```

`run-compare.sh` builds the backend once, then for each mode: stops any previous
backend (waiting for it to actually exit), cleans `cajun_persistence`, starts the
backend in that mode, waits for health, verifies the reported mode, runs
`bench.mjs`, and finally renders [`RESULTS.md`](./RESULTS.md).

`bench.mjs` runs standalone too, against whatever backend is already up:

```bash
BASE_URL=http://localhost:7070 DURATION_S=20 VUS=50 node bench.mjs > out.json
```

Knobs (env): `DURATION_S`, `VUS`, `SEATS`, `WORKLOAD` (`journey` | `churn`),
`FOCUS_SHOW` (hammer one show instead of spreading). `report.mjs` turns one or
more `bench.mjs` JSON outputs into the Markdown table.

## What the comparison shows

See [`RESULTS.md`](./RESULTS.md) for a full run. The shape:

- **Durability isn't free.** Every hold/confirm/release in the stateful modes is
  written to the message journal before the reply. Cajun batches those writes
  (100 ms / 100-message default batches), so the seat actor's per-op latency
  jumps from single-digit milliseconds (memory) to ~90 ms (stateful) — the batch
  flush window — and whole-system throughput drops several-fold. In return, a
  restart rebuilds every show's holds and bookings from the journal instead of
  starting empty. (Try it: book seats in `stateful` mode, restart the backend,
  and the booked seats are still there.)
- **Backpressure is a safety net, not a speed-up.** Because the batched journal
  keeps a single actor from backing up, the mailbox stays nearly empty under a
  normal spread load, so `stateful-backpressure` performs the same as `stateful`.
  Its value shows only under genuine overload of a single actor, where the
  bounded mailbox caps memory and pushes back (BLOCK) instead of growing without
  bound. To try to provoke it, concentrate load on one actor:

  ```bash
  WORKLOAD=churn VUS=250 SEATS=1 FOCUS_SHOW=<show-id> \
    BASE_URL=http://localhost:7070 node bench.mjs
  ```

  Watch `cinetor_backpressure_*` in `/api/metrics` (also surfaced in the report).
  BLOCK is deliberate: a booking must never be silently dropped, so under
  pressure the sender slows (latency, then the 5 s ask-timeout) rather than
  losing a hold or confirm the way DROP_NEW/DROP_OLDEST would.

## A wrinkle worth knowing: cold-start

A persistent actor pays a one-time state-initialisation cost (snapshot lookup +
journal replay) on its first message — a few seconds while cold, and the cost is
per-actor but parallelises. Spawning actors lazily under concurrent load means
the first request for each show can exceed the 5 s ask-timeout, so the backend
**warms every actor at startup** (concurrently) before serving. `bench.mjs` also
does a warm-up pass so its measured window is steady-state.

## Files

```
compare/
├── bench.mjs        # dependency-free Node load driver + metrics scrape
├── report.mjs       # bench JSON -> RESULTS.md table
├── run-compare.sh   # orchestrates all three modes end to end
├── RESULTS.md       # generated report (checked in for reference)
└── results/         # raw per-mode JSON + server logs (gitignored)
```
