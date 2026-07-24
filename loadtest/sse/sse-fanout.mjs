// SSE fan-out load + broadcast-correctness check (Node 18+, no dependencies).
//
// k6 v0.54 has no SSE client, so this covers the realtime path directly. It:
//   1. opens N concurrent SSE subscribers on one show's /stream,
//   2. books a free seat on that show once all subscribers are connected,
//   3. verifies every subscriber receives the seats-update carrying that seat,
//   4. reports fan-out delivery ratio and broadcast latency (min/avg/p95/max).
//
// A hold must NOT broadcast (holds are invisible to the public map), so the
// script also asserts that the hold alone produces no seats-update — only the
// confirm does.
//
//   BASE_URL=http://localhost:7070 SUBSCRIBERS=200 node sse/sse-fanout.mjs

const BASE = process.env.BASE_URL || 'http://localhost:7070';
const SHOW = process.env.SHOW_ID || 'bom-spirited-regal-bom-1215';
const N = parseInt(process.env.SUBSCRIBERS || '200', 10);
const SETTLE_MS = parseInt(process.env.SETTLE_MS || '3000', 10);

const JSON_HEADERS = { 'Content-Type': 'application/json' };

async function getJson(path) {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`GET ${path} -> ${res.status}`);
  return res.json();
}

function allSeatIds(rows, cols) {
  const ids = [];
  for (let r = 0; r < rows; r++) {
    const rowLabel = String.fromCharCode(65 + r);
    for (let c = 1; c <= cols; c++) ids.push(rowLabel + c);
  }
  return ids;
}

// One SSE subscriber. Resolves `connected` after the initial snapshot arrives,
// and records the arrival time of the first seats-update that contains `seat`.
function subscribe(id, seat, state) {
  return new Promise((resolveConnected) => {
    const controller = new AbortController();
    state.controllers.push(controller);

    fetch(`${BASE}/api/shows/${SHOW}/stream`, {
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    })
      .then(async (res) => {
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        let sawSnapshot = false;

        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });

          let sep;
          while ((sep = buf.indexOf('\n\n')) >= 0) {
            const frame = buf.slice(0, sep);
            buf = buf.slice(sep + 2);

            const dataLine = frame.split('\n').find((l) => l.startsWith('data:'));
            if (!dataLine) continue;
            let payload;
            try {
              payload = JSON.parse(dataLine.slice(5).trim());
            } catch {
              continue;
            }
            const booked = new Set(payload.booked || []);

            if (!sawSnapshot) {
              // The first frame is the connect-time snapshot.
              sawSnapshot = true;
              resolveConnected();
              continue;
            }
            // Classify by PAYLOAD, not by timing. Only a confirm books our seat,
            // so the payload alone tells the two broadcasts apart — no reliance on
            // a phase/time boundary that a delayed frame could cross:
            //   - contains our seat  -> the confirm broadcast (legitimate delivery);
            //   - lacks our seat     -> a forbidden broadcast. A hold must not
            //     broadcast at all, and its payload (the public booked list)
            //     excludes held seats, so an erroneous hold broadcast is exactly a
            //     post-snapshot frame WITHOUT our seat — whenever it arrives.
            if (booked.has(seat)) {
              if (state.confirmSentAt && !state.received.has(id)) {
                state.received.set(id, Date.now() - state.confirmSentAt);
              }
            } else {
              state.leaked.add(id); // BUG: a broadcast reached subscribers pre-confirm
            }
          }
        }
      })
      .catch(() => {
        /* aborted at teardown */
      });
  });
}

function pct(sorted, p) {
  if (!sorted.length) return NaN;
  const idx = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[idx];
}

async function main() {
  console.log(`SSE fan-out: ${N} subscribers on show ${SHOW} @ ${BASE}`);

  const detail = await getJson(`/api/shows/${SHOW}`);
  const booked = new Set(detail.bookedSeats || []);
  const free = allSeatIds(detail.rows, detail.cols).filter((s) => !booked.has(s));
  if (!free.length) {
    console.error(`show ${SHOW} is sold out — set SHOW_ID to a show with free seats`);
    process.exit(2);
  }
  const seat = free[Math.floor(Math.random() * free.length)];

  const state = {
    controllers: [],
    received: new Map(), // subscriberId -> latency ms (confirm-broadcast delivery)
    leaked: new Set(), // subscriberIds that saw a forbidden broadcast (no-seat payload)
    confirmSentAt: null, // set just before we POST /confirm
  };

  // Open all subscribers and wait for every one to receive its snapshot.
  const connected = [];
  for (let i = 0; i < N; i++) connected.push(subscribe(i, seat, state));
  await Promise.all(connected);
  console.log(`all ${N} subscribers connected`);

  const holderId = `sse-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

  // Hold first: this must NOT trigger any broadcast.
  const holdRes = await fetch(`${BASE}/api/shows/${SHOW}/hold`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ seatIds: [seat], holderId }),
  });
  if (holdRes.status !== 200) {
    console.error(`hold failed: ${holdRes.status} ${await holdRes.text()}`);
    process.exit(2);
  }
  const hold = await holdRes.json();
  // Brief pause so an immediate hold broadcast (if the backend had that bug)
  // is clearly observed during the hold window. The leak verdict is computed at
  // the END, though, so even a slow/delayed forbidden frame is still caught.
  await new Promise((r) => setTimeout(r, 500));

  // Confirm: the only event that should broadcast. Record the send time so we
  // can attribute confirm-broadcast latency; classification is by payload, not
  // by this timestamp.
  state.confirmSentAt = Date.now();
  const confRes = await fetch(`${BASE}/api/shows/${SHOW}/confirm`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ holdId: hold.holdId, holderId, customerName: 'SSELoadTest' }),
  });
  if (confRes.status !== 200) {
    console.error(`confirm failed: ${confRes.status} ${await confRes.text()}`);
    process.exit(2);
  }

  // Let the fan-out settle, then tear down the connections.
  await new Promise((r) => setTimeout(r, SETTLE_MS));
  state.controllers.forEach((c) => c.abort());

  // Evaluate the leak verdict only now, after the settle window — a forbidden
  // broadcast delayed past the hold window still lands in state.leaked.
  const leakedOnHold = state.leaked.size > 0;
  const latencies = [...state.received.values()].sort((a, b) => a - b);
  const delivered = state.received.size;
  const ratio = ((delivered / N) * 100).toFixed(1);

  console.log('\n--- results ---');
  console.log(`seat booked          : ${seat}`);
  console.log(`hold caused broadcast: ${leakedOnHold ? 'YES (BUG)' : 'no (correct)'}`);
  console.log(`delivered            : ${delivered}/${N} (${ratio}%)`);
  if (latencies.length) {
    const avg = (latencies.reduce((a, b) => a + b, 0) / latencies.length).toFixed(1);
    console.log(`broadcast latency ms : min=${latencies[0]} avg=${avg} p95=${pct(latencies, 95)} max=${latencies[latencies.length - 1]}`);
  }

  const ok = delivered === N && !leakedOnHold;
  console.log(`\nRESULT: ${ok ? 'PASS' : 'FAIL'}`);
  process.exit(ok ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
