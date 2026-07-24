// Saturation probe: the single-actor throughput ceiling.
//
// There is exactly one actor per show, and it processes its mailbox one message
// at a time. Pointing an ever-increasing request rate at ONE show finds the
// throughput ceiling of a single actor and shows how the system degrades past
// it: latency climbs, then the 5s ask-timeout in BookingService trips and the
// endpoint returns 500. Watch http_req_duration and the 500 rate climb — the
// thresholds here are observational markers, not pass/fail goals.
//
//   BASE_URL=http://localhost:7070 k6 run scenarios/single-show-ceiling.js
//   # target the whole system instead of one actor by spreading across shows:
//   #   use booking-journey.js, which picks a random show each iteration.

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from '../lib/config.js';

const SHOW = __ENV.SHOW_ID || 'blr-interstellar-prestige-blr-1030';

export const options = {
  scenarios: {
    ceiling: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 800,
      stages: [
        { duration: '20s', target: 1000 },
        { duration: '20s', target: 3000 },
        { duration: '20s', target: 6000 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Observational: expected to break at high rate — that break IS the finding.
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  // GET /api/shows/{id} resolves the booked-seat snapshot through the show's
  // single actor, so hammering one show measures that actor's read ceiling.
  const r = http.get(`${BASE_URL}/api/shows/${SHOW}`);
  check(r, { 'snapshot 200': (res) => res.status === 200 });
}
