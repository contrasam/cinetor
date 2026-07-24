// Shared configuration and helpers for the k6 scenarios.
//
// The system under test is the Cinetor backend only (Javalin + Cajun actors).
// Point the suite at a running backend with BASE_URL, e.g.
//   BASE_URL=http://localhost:7070 k6 run scenarios/booking-journey.js

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:7070';

export const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// A holderId must be unique per booking session and stable across the
// hold -> confirm chain (the actor rejects a confirm from a different holder).
// __VU and __ITER are k6 globals, so this is unique across the whole run.
export function uid(prefix = 'u') {
  return `${prefix}-${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 10)}`;
}
