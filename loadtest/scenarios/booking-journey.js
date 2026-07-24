// Write-heavy load: the full booking journey, spread across all shows.
//
// Each iteration is one user: discover free seats -> hold -> then cancel,
// abandon, or pay. Along the way it asserts the booking invariants inline, so
// this is a load test AND a correctness test at throughput.
//
//   BASE_URL=http://localhost:7070 k6 run scenarios/booking-journey.js
//
// Note on state: the backend keeps seat state in memory and confirmed bookings
// only accumulate — a sustained run WILL sell shows out. That is expected: a
// 409 (sold out / seat taken by a racing user) is a normal outcome, not a
// failure, so it is excluded from the error rate below. To keep seats churning
// across a long run, start the backend with a short hold window, e.g.
// HOLD_SECONDS=15, so abandoned holds free up quickly.

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS, uid } from '../lib/config.js';
import { discoverShows, availableSeats, pickN } from '../lib/catalog.js';

// A 409 is an expected business outcome here (sold out / lost a seat race),
// so it must not count against http_req_failed.
http.setResponseCallback(http.expectedStatuses(200, 409));

const holdsOk = new Counter('biz_holds_ok');
const holdsRejected = new Counter('biz_holds_rejected');
const confirmsOk = new Counter('biz_confirms_ok');
const confirmsRejected = new Counter('biz_confirms_rejected');
const releases = new Counter('biz_releases');
// Any non-zero value here means the backend broke a correctness guarantee.
const invariantViolations = new Counter('biz_invariant_violations');

export const options = {
  scenarios: {
    journey: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 30 },
        { duration: '30s', target: 30 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Hard gates: no server errors, and no broken invariants.
    http_req_failed: ['rate<0.01'],
    biz_invariant_violations: ['count<1'],
    // Latency gate measured only over expected (200/409) responses.
    'http_req_duration{expected_response:true}': ['p(95)<400'],
  },
};

export function setup() {
  const shows = discoverShows();
  if (!shows.length) throw new Error('no shows discovered — is the backend up at ' + BASE_URL + '?');
  return { shows };
}

export default function (data) {
  const show = data.shows[Math.floor(Math.random() * data.shows.length)];
  const holderId = uid('holder');

  const free = availableSeats(show.id);
  if (free.length === 0) return; // show sold out — nothing to book
  const seats = pickN(free, 1 + Math.floor(Math.random() * 3));

  // --- Phase 1: hold -------------------------------------------------------
  const holdRes = http.post(
    `${BASE_URL}/api/shows/${show.id}/hold`,
    JSON.stringify({ seatIds: seats, holderId }),
    JSON_HEADERS,
  );
  if (holdRes.status === 409) {
    holdsRejected.add(1); // someone raced us to a seat — expected
    return;
  }
  const held = holdRes.json();
  const heldOk = check(holdRes, {
    'hold 200': (r) => r.status === 200,
    'hold status HELD': () => held.status === 'HELD',
    'hold has holdId': () => typeof held.holdId === 'string' && held.holdId.startsWith('HOLD-'),
    'hold ttl positive': () => held.ttlSeconds > 0,
    'hold returns my seats': () => seats.every((s) => held.seats.includes(s)),
  });
  if (!heldOk) {
    invariantViolations.add(1);
    return;
  }
  holdsOk.add(1);

  // Model real behaviour: ~15% cancel, ~10% abandon (let the hold expire),
  // the rest pay.
  const roll = Math.random();

  if (roll < 0.15) {
    const rel = http.post(
      `${BASE_URL}/api/shows/${show.id}/release`,
      JSON.stringify({ holdId: held.holdId, holderId }),
      JSON_HEADERS,
    );
    const relOk = check(rel, {
      'release 200': (r) => r.status === 200,
      // Correctness: releasing my own valid hold must be acknowledged.
      'release acknowledged': (r) => r.json().released === true,
    });
    if (!relOk) {
      invariantViolations.add(1);
      return;
    }
    releases.add(1);
    return;
  }
  if (roll < 0.25) {
    return; // abandon: server-side expiry reclaims the seats
  }

  // --- Phase 2: confirm ----------------------------------------------------
  const confRes = http.post(
    `${BASE_URL}/api/shows/${show.id}/confirm`,
    JSON.stringify({ holdId: held.holdId, holderId, customerName: 'LoadTest' }),
    JSON_HEADERS,
  );
  if (confRes.status === 409) {
    confirmsRejected.add(1); // hold expired before we paid — expected under load
    return;
  }
  const conf = confRes.json();
  const confOk = check(confRes, {
    'confirm 200': (r) => r.status === 200,
    'confirm status CONFIRMED': () => conf.status === 'CONFIRMED',
    // Correctness: every seat I held is now in the public booked set.
    'my seats are booked': () => seats.every((s) => conf.bookedSeats.includes(s)),
    // Correctness: the public booked set has no duplicates (no double-book).
    'no duplicate booked seats': () => new Set(conf.bookedSeats).size === conf.bookedSeats.length,
    // Correctness: the confirmed seat set matches exactly what I held.
    'confirmed seats match held': () =>
      conf.seats.length === seats.length && seats.every((s) => conf.seats.includes(s)),
  });
  if (!confOk) {
    invariantViolations.add(1);
    return;
  }
  confirmsOk.add(1);
}
