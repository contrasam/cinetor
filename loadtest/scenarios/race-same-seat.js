// Correctness centrepiece: the thundering herd on a single seat.
//
// N virtual users fire a hold for the SAME seat at the same moment. The system's
// core promise is that exactly one wins and everyone else gets a clean 409 — no
// double-hold, no 5xx, no timeout. This is the actor's serialised mailbox being
// proven on purpose rather than incidentally.
//
//   BASE_URL=http://localhost:7070 RACE_VUS=200 k6 run scenarios/race-same-seat.js
//
// IMPORTANT: run this on its own against a show with the target seat free
// (ideally a freshly started backend). If another scenario books the seat
// concurrently, "exactly one winner" is no longer a meaningful assertion.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS, uid } from '../lib/config.js';
import { availableSeats } from '../lib/catalog.js';

// 409 is the expected outcome for all the losers.
http.setResponseCallback(http.expectedStatuses(200, 409));

const held = new Counter('race_held'); // holds that succeeded
const rejected = new Counter('race_rejected'); // clean 409s (losers)
const errors = new Counter('race_errors'); // 5xx / timeouts — must be zero

const SHOW = __ENV.SHOW_ID || 'blr-oppenheimer-orion-blr-1200';
const VUS = parseInt(__ENV.RACE_VUS || '100', 10);

export const options = {
  scenarios: {
    stampede: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS, // each VU fires exactly one hold, all at once
      maxDuration: '30s',
    },
  },
  thresholds: {
    // THE invariant: out of N racers for one seat, exactly one wins the hold.
    race_held: ['count>0', 'count<2'],
    // Every loser must get a clean rejection, never a server error/timeout.
    race_errors: ['count<1'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const free = availableSeats(SHOW);
  if (!free.length) {
    throw new Error(
      `show ${SHOW} has no free seats. Run against a fresh backend or set SHOW_ID to an untouched show.`,
    );
  }
  const seat = free[Math.floor(Math.random() * free.length)];
  return { seat };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/shows/${SHOW}/hold`,
    JSON.stringify({ seatIds: [data.seat], holderId: uid('racer') }),
    JSON_HEADERS,
  );

  if (res.status === 200 && res.json().status === 'HELD') {
    held.add(1);
  } else if (res.status === 409) {
    rejected.add(1);
  } else {
    errors.add(1);
  }

  check(res, { 'no server error': (r) => r.status === 200 || r.status === 409 });
}

export function teardown(data) {
  // The winner's hold either expires or persists; either way the public
  // snapshot must never show the seat as booked (a hold is not a booking).
  // Throwing here fails the whole run (k6 exits non-zero) — a threshold can't
  // see this check because it only runs once, after all iterations complete.
  const detail = http.get(`${BASE_URL}/api/shows/${SHOW}`).json();
  const seatBooked = (detail.bookedSeats || []).includes(data.seat);
  if (seatBooked) {
    throw new Error(
      `INVARIANT VIOLATION: held seat ${data.seat} leaked into the public booked set`,
    );
  }
}
