// Cinetor actor-mode comparison benchmark (dependency-free, Node 18+).
//
// Drives a write-heavy hold -> confirm/release workload against a *single*
// running backend and records client-side latency/throughput plus a scrape of
// the server's Prometheus metrics. It does NOT know which actor mode the server
// is in — you start the backend in one mode, point this at it, and it captures
// that mode's numbers. `run-compare.sh` orchestrates all three modes and feeds
// the JSON outputs to `report.mjs`.
//
// Usage:
//   BASE_URL=http://localhost:7070 MODE=memory DURATION_S=20 VUS=50 \
//     node bench.mjs > results/memory.json
//
// Env knobs:
//   BASE_URL    backend base url            (default http://localhost:7070)
//   MODE        label recorded in output    (default: read from /api/config)
//   DURATION_S  seconds of steady load      (default 20)
//   VUS         concurrent virtual users    (default 50)
//   SEATS       seats held per iteration    (default 2)
//   FOCUS_SHOW  if set, hammer only this one show (exercises a single actor /
//               backpressure); otherwise spread across all shows.
//   WORKLOAD    'journey' (default): hold -> pay/cancel/abandon, seats sell out.
//               'churn': hold -> release every time, so seats never sell out and
//               the actor journals on every op — the way to keep one actor
//               saturated long enough for backpressure to engage.

const BASE_URL = process.env.BASE_URL || 'http://localhost:7070';
const DURATION_S = Number(process.env.DURATION_S || 20);
const VUS = Number(process.env.VUS || 50);
const SEATS = Number(process.env.SEATS || 2);
const FOCUS_SHOW = process.env.FOCUS_SHOW || null;
const WORKLOAD = process.env.WORKLOAD || 'journey';

const jsonHeaders = { 'Content-Type': 'application/json' };

function uid(prefix = 'u') {
  return `${prefix}-${Math.random().toString(36).slice(2, 12)}`;
}

async function getJson(path) {
  const res = await fetch(`${BASE_URL}${path}`);
  if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`);
  return res.json();
}

// Discover every bookable show by walking the public browse hierarchy.
async function discoverShows() {
  const out = [];
  const cities = await getJson('/api/cities');
  for (const city of cities) {
    const movies = await getJson(`/api/cities/${city.id}/movies`);
    for (const movie of movies) {
      const groups = await getJson(`/api/cities/${city.id}/movies/${movie.id}/shows`);
      for (const g of groups) for (const s of g.shows) out.push({ id: s.id, rows: s.rows, cols: s.cols });
    }
  }
  return out;
}

function allSeatIds(rows, cols) {
  const ids = [];
  for (let r = 0; r < rows; r++) {
    const rowLabel = String.fromCharCode(65 + r);
    for (let c = 1; c <= cols; c++) ids.push(rowLabel + c);
  }
  return ids;
}

function pickN(arr, n) {
  const copy = arr.slice();
  const out = [];
  for (let i = 0; i < n && copy.length; i++) out.push(copy.splice((Math.random() * copy.length) | 0, 1)[0]);
  return out;
}

// A tiny timed POST that never throws: returns {status, ms, body}.
async function timedPost(path, payload) {
  const start = performance.now();
  try {
    const res = await fetch(`${BASE_URL}${path}`, {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify(payload),
    });
    const ms = performance.now() - start;
    let body = null;
    try { body = await res.json(); } catch { /* ignore */ }
    return { status: res.status, ms, body };
  } catch (e) {
    return { status: 0, ms: performance.now() - start, body: { error: String(e) } };
  }
}

const lat = { hold: [], confirm: [], release: [] };
const counts = {
  iterations: 0, held: 0, holdRejected: 0, confirmed: 0, confirmRejected: 0,
  released: 0, errors: 0, readErrors: 0, http5xx: 0,
};

// One closed-loop virtual user: hold seats, then confirm / release / abandon.
async function vu(shows, deadline) {
  while (performance.now() < deadline) {
    counts.iterations++;
    const show = FOCUS_SHOW
      ? shows.find((s) => s.id === FOCUS_SHOW) || shows[0]
      : shows[(Math.random() * shows.length) | 0];
    const holder = uid();

    // Pick candidate seats from the public snapshot (held seats aren't in it, so
    // a hold can still lose a race and 409 — that's expected, not an error).
    // GET show detail resolves the booked snapshot through the actor too, so in
    // the stateful/backpressure modes it can time out under load (500). Count
    // those reads separately from write-path errors.
    let detail;
    try {
      detail = await getJson(`/api/shows/${show.id}`);
    } catch {
      counts.readErrors++;
      continue;
    }
    const booked = new Set(detail.bookedSeats || []);
    const free = allSeatIds(detail.rows, detail.cols).filter((s) => !booked.has(s));
    if (free.length < SEATS) continue; // this show is (near) sold out; move on
    const seatIds = pickN(free, SEATS);

    const hold = await timedPost(`/api/shows/${show.id}/hold`, { seatIds, holderId: holder });
    lat.hold.push(hold.ms);
    if (hold.status >= 500 || hold.status === 0) { counts.http5xx++; counts.errors++; continue; }
    if (hold.status === 409) { counts.holdRejected++; continue; }
    if (hold.status !== 200 || !hold.body || hold.body.status !== 'HELD') { counts.errors++; continue; }
    counts.held++;
    const holdId = hold.body.holdId;

    // In 'churn' mode always release, so seats recycle and the actor keeps
    // journaling — the load pattern that actually saturates one actor's mailbox.
    const roll = WORKLOAD === 'churn' ? 0.6 : Math.random();
    if (roll < 0.5) {
      // Pay.
      const confirm = await timedPost(`/api/shows/${show.id}/confirm`,
        { holdId, holderId: holder, customerName: 'Bench' });
      lat.confirm.push(confirm.ms);
      if (confirm.status >= 500 || confirm.status === 0) { counts.http5xx++; counts.errors++; }
      else if (confirm.status === 409) counts.confirmRejected++;
      else if (confirm.status === 200 && confirm.body && confirm.body.status === 'CONFIRMED') counts.confirmed++;
      else counts.errors++;
    } else if (roll < 0.85 || WORKLOAD === 'churn') {
      // Cancel (always, in churn mode).
      const rel = await timedPost(`/api/shows/${show.id}/release`, { holdId, holderId: holder });
      lat.release.push(rel.ms);
      if (rel.status >= 500 || rel.status === 0) { counts.http5xx++; counts.errors++; }
      else counts.released++;
    }
    // else: abandon — let the server-side hold expiry recycle the seats.
  }
}

function pct(arr, p) {
  if (!arr.length) return 0;
  const s = arr.slice().sort((a, b) => a - b);
  return Math.round(s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))] * 100) / 100;
}

function summarize(arr) {
  return { count: arr.length, p50: pct(arr, 50), p95: pct(arr, 95), p99: pct(arr, 99), max: pct(arr, 100) };
}

// Pull the handful of server-side series that make the mode difference legible.
async function scrapeMetrics() {
  const text = await (await fetch(`${BASE_URL}/api/metrics`)).text();
  const want = [
    'cinetor_actor_ask_seconds',      // per-op actor latency (mailbox + processing)
    'cinetor_actor_ask_errors_total',
    'cinetor_hold_total', 'cinetor_confirm_total',
    'cinetor_backpressure_',          // fill ratio / active actors / dropped
    'cinetor_actor_mode_info',
    'process_cpu_usage',
    'jvm_memory_used_bytes',
  ];
  const lines = text.split('\n').filter((l) => l && !l.startsWith('#') && want.some((w) => l.startsWith(w)));
  return lines;
}

async function main() {
  let mode = process.env.MODE;
  try {
    const cfg = await getJson('/api/config');
    if (!mode) mode = cfg.actorMode || 'unknown';
  } catch { if (!mode) mode = 'unknown'; }

  const shows = await discoverShows();

  // Warm-up: touch every show's actor *concurrently* before the timed load. In
  // the stateful modes the first touch of a show lazily spawns its persistent
  // actor and pays a one-time init cost (a few seconds cold). Firing them all at
  // once overlaps that cost (the server also warms eagerly at startup, so this is
  // usually already done); a second pass confirms everything is hot before we
  // start measuring, keeping the window steady-state.
  await Promise.all(shows.map((s) => getJson(`/api/shows/${s.id}`).catch(() => null)));
  await new Promise((r) => setTimeout(r, 1000));
  await Promise.all(shows.map((s) => getJson(`/api/shows/${s.id}`).catch(() => null)));

  const start = performance.now();
  const deadline = start + DURATION_S * 1000;
  await Promise.all(Array.from({ length: VUS }, () => vu(shows, deadline)));
  const elapsedS = (performance.now() - start) / 1000;

  const completed = counts.confirmed + counts.confirmRejected + counts.released + counts.holdRejected;
  const result = {
    mode,
    config: { baseUrl: BASE_URL, durationS: DURATION_S, vus: VUS, seats: SEATS, workload: WORKLOAD, focusShow: FOCUS_SHOW, shows: shows.length },
    elapsedS: Math.round(elapsedS * 100) / 100,
    throughput: {
      iterationsPerSec: Math.round((counts.iterations / elapsedS) * 10) / 10,
      completedOpsPerSec: Math.round((completed / elapsedS) * 10) / 10,
      confirmsPerSec: Math.round((counts.confirmed / elapsedS) * 10) / 10,
    },
    counts,
    latencyMs: { hold: summarize(lat.hold), confirm: summarize(lat.confirm), release: summarize(lat.release) },
    serverMetrics: await scrapeMetrics(),
  };
  process.stdout.write(JSON.stringify(result, null, 2) + '\n');
}

main().catch((e) => { console.error(e); process.exit(1); });
