# Cinetor backend — load & correctness suite

Load tests for the **backend only** (Javalin + Cajun actors on `:7070`) that
double as correctness checks. The interesting property of this system isn't raw
throughput — it's that **a seat can never be double-booked**, because every read
and write for a show is serialised through that show's single actor. So this
suite runs two tracks at once:

- **Load** — how much the HTTP layer + actors sustain, and where they degrade.
- **Correctness under concurrency** — the invariants still hold while it's hot.

Tooling: [**k6**](https://k6.io) for the HTTP scenarios (load + inline
assertions via `check` and pass/fail `thresholds`), plus a small dependency-free
**Node** script for the SSE realtime path (k6 v0.54 has no SSE client).

## Layout

```
loadtest/
├── lib/
│   ├── config.js              # BASE_URL, headers, unique holderId generator
│   └── catalog.js             # show discovery + seat helpers
├── scenarios/
│   ├── browse.js              # read-heavy: catalogue browse funnel
│   ├── booking-journey.js     # write-heavy: hold → confirm/release + invariants
│   ├── race-same-seat.js      # correctness: N racers, one seat, exactly one wins
│   └── single-show-ceiling.js # saturation: one actor's throughput ceiling
├── sse/
│   └── sse-fanout.mjs         # SSE fan-out + broadcast-correctness (Node)
├── compare/                   # actor-mode comparison (memory vs stateful vs backpressure)
│   ├── bench.mjs              # dependency-free load driver + metrics scrape
│   ├── run-compare.sh         # runs all three modes -> RESULTS.md
│   └── README.md
├── run.sh                     # convenience runner
└── README.md
```

> **Comparing actor modes.** The seat actor can run in-memory, persistent
> (`stateful`), or persistent-with-backpressure. The `compare/` harness drives
> the same workload against each and renders a side-by-side report — see
> [`compare/README.md`](compare/README.md).

## Prerequisites

- **k6** — https://grafana.com/docs/k6/latest/set-up/install-k6/ (or drop a
  static binary on your `PATH`).
- **Node 18+** — for the SSE check only (`fetch` + streams are built in).
- A **running backend**. From `backend/`:

  ```bash
  # A short hold window keeps seats churning during a sustained run.
  HOLD_SECONDS=15 ./gradlew run
  ```

## Running

```bash
cd loadtest
BASE_URL=http://localhost:7070 ./run.sh journey      # or: browse | race | ceiling | sse | all
```

`run.sh` just wraps `k6 run scenarios/<name>.js` and `node sse/sse-fanout.mjs`.
Set `K6=/path/to/k6` if it isn't on your `PATH`. Every script also runs directly,
e.g. `RACE_VUS=500 SHOW_ID=blr-oppenheimer-orion-blr-1200 k6 run scenarios/race-same-seat.js`.

## The scenarios

| Scenario | What it drives | Pass/fail gate (k6 thresholds) |
|---|---|---|
| **browse** | Read funnel: `config → movies → shows → show detail`. `GET /shows/{id}` resolves booked seats through the actor. | `http_req_failed < 1%`, `p95 < 250ms`, `p99 < 500ms` |
| **booking-journey** | One user per iteration across all shows: hold → (15% cancel, 10% abandon, rest pay). Asserts booking invariants inline. | `http_req_failed < 1%`, **`biz_invariant_violations == 0`**, `p95 < 400ms` |
| **race-same-seat** | N VUs hold the **same** seat simultaneously. | **`race_held` is exactly 1**, `race_errors == 0`, `http_req_failed < 1%` |
| **single-show-ceiling** | Ramping arrival rate at **one** show to find the per-actor ceiling. | Observational — watch latency and the 500 rate climb |
| **sse-fanout** (Node) | N SSE subscribers on one show; book a seat; verify every subscriber gets it. | 100% delivery **and** hold caused no broadcast |

> **Isolation precondition (race-same-seat and sse-fanout).** Both are controlled
> correctness probes and assume they are the only booker on their target show —
> run them against a dedicated/idle show, ideally a freshly started backend. For
> `sse-fanout` specifically: its leak check flags any broadcast that adds no new
> seat as a forbidden hold broadcast, which is exact only without concurrent
> third-party confirms. Under simultaneous confirms on the same show, SSE frames
> can arrive out of order and a delayed cumulative confirm is indistinguishable
> from a hold rebroadcast at the subscriber, so a shared show can yield a spurious
> leak. The frame classifier itself is unit-tested — run `node sse/classify.test.mjs`.

## Correctness invariants asserted

- **No double-hold** — `race-same-seat`: out of N concurrent racers for one seat,
  exactly one gets `HELD`; every other gets a clean `409`, never a `5xx`/timeout.
- **No double-book** — `booking-journey`: the public booked set never contains a
  duplicate, and confirmed seats exactly match the held seats.
- **Holds block but don't broadcast** — `sse-fanout`: a `hold` produces no
  `seats-update`; only `confirm` does, and it reaches every subscriber.
- **Hold contract** — a `HELD` reply carries a `HOLD-…` id, a positive TTL, and
  the requested seats.

Any violation increments a metric wired to a hard threshold, so the run exits
non-zero (k6 exit code `99`) — usable directly as a CI gate.

## System-specific gotchas (why the scripts do what they do)

- **State is in-memory and monotonic.** Confirmed bookings only accumulate; a
  sustained write run **will sell shows out**. A `409` (sold out, or a seat lost
  to a racing user) is therefore an *expected* outcome — the write scenarios call
  `http.setResponseCallback(http.expectedStatuses(200, 409))` so 409s don't count
  as errors. Start the backend with a short `HOLD_SECONDS` to recycle abandoned
  holds, or restart it between runs for a clean slate.
- **One actor per show = one thread per show.** `single-show-ceiling` hammers a
  single show to find the per-actor ceiling; `booking-journey` spreads across all
  ~30 shows to exercise whole-system throughput. Very different numbers — pick the
  one that matches your question.
- **`holderId` is load-bearing.** A hold needs a non-blank `holderId`, and the
  matching `confirm`/`release` must reuse it (a foreign holder gets a `409`). Each
  VU generates a unique, stable id per iteration (`lib/config.js:uid`).
- **Saturation surfaces as HTTP 500 — and now in the metrics.** `BookingService`
  uses a 5s actor ask-timeout; on timeout the handler throws and Javalin returns
  `500`. Watch the client-side 500 rate together with `cinetor_actor_ask_seconds`
  (climbing p95/p99) and `cinetor_actor_ask_errors_total` (see below) for the
  server's side of the same story.
- **The public snapshot excludes holds.** `availableSeats` reads the snapshot, so
  under concurrency two VUs can both pick the "same free" seat and one loses the
  hold race — that's correct behaviour and shows up as `biz_holds_rejected`.

## Server-side metrics (during a run)

The client-side k6 numbers only see the outside of the box. The backend also
exposes Prometheus metrics at **`GET /api/metrics`** (Micrometer), which show the
one thing k6 can't: what the actors are doing. Scrape it with a Prometheus
server, or just eyeball it during a run:

```bash
watch -n1 'curl -s localhost:7070/api/metrics | grep -E "^cinetor_(actor_ask_seconds\{|hold_total|confirm_total|actor_ask_errors|sse_)"'
```

Key series:

| Metric | Why it matters |
|---|---|
| `cinetor_actor_ask_seconds{op="hold\|confirm\|release\|snapshot"}` | Per-op seat-actor latency (mailbox wait + processing), with p50/p95/p99. This is what the 5s ask-timeout is racing — the truest saturation signal. |
| `cinetor_actor_ask_errors_total{op=...}` | Asks that timed out/failed. Non-zero = the actor couldn't keep up and clients saw HTTP 500. |
| `cinetor_hold_total{result="held\|rejected"}` | Hold outcomes. A high `rejected` share is contention (many users racing for the same seats) — expected, and worth watching. |
| `cinetor_confirm_total{result="confirmed\|rejected"}` | Conversions vs holds that expired before payment. |
| `cinetor_release_total{result=...}` | Explicit cancellations. |
| `cinetor_http_server_requests_seconds{route,method,status}` | Per-endpoint latency/throughput, `route` as the matched template so labels stay low-cardinality. |
| `cinetor_sse_clients`, `cinetor_sse_broadcasts_total` | Live SSE subscriber count and fan-out volume. |
| `cinetor_actor_mode_info{mode=...}` | Which actor mode the backend is running (`memory` \| `stateful` \| `stateful-backpressure`). |
| `cinetor_backpressure_max_fill_ratio`, `..._max_mailbox_size`, `..._active_actors`, `..._dropped` | Mailbox pressure across the seat actors — only meaningful in `stateful-backpressure` mode; 0 otherwise. |
| `jvm_*`, `process_cpu_usage`, `jvm_gc_*` | Correlate latency spikes with GC pauses / memory / CPU. |

To correlate client and server: run a scenario, and after it finishes the
counters above should reconcile with the k6 `biz_*` counters — e.g. k6
`biz_confirms_ok` ≈ `cinetor_confirm_total{result="confirmed"}` for that run.

## Verified sample run

Against the backend on a single dev container (`HOLD_SECONDS=10`), for reference:

- **race-same-seat** (200 VUs, one seat): `race_held = 1`, `race_rejected = 199`,
  `race_errors = 0`, `http_req_failed = 0%`. ✅ exactly one winner.
- **booking-journey** (30 VUs, 55s): 242k requests, `0%` failed,
  **0 invariant violations**, p95 ≈ 14ms; 2.1k confirms, 2.8k holds,
  52k clean 409s from lost seat-races.
- **sse-fanout** (150 subscribers): 150/150 delivered, hold caused no broadcast,
  broadcast latency avg ≈ 33ms.
- **single-show-ceiling**: one actor sustained ~5.6k snapshot reads/s at peak with
  sub-millisecond p95 and zero errors.

Your numbers will differ with hardware and `BASE_URL` latency; treat these as a
shape, not a target.
