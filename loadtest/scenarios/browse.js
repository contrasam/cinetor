// Read-heavy load: the catalogue browse funnel.
//
// Exercises the stateless read path plus GET /api/shows/{id}, which reaches the
// per-show actor via GetSnapshot. This is the "everyone is browsing, few are
// buying" profile and should stay fast and error-free.
//
//   BASE_URL=http://localhost:7070 k6 run scenarios/browse.js

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { BASE_URL } from '../lib/config.js';

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 50 },
        { duration: '30s', target: 50 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250', 'p(99)<500'],
  },
};

const CITIES = ['blr', 'bom', 'hyd'];

export default function () {
  group('browse funnel', () => {
    check(http.get(`${BASE_URL}/api/config`), { 'config 200': (r) => r.status === 200 });

    const city = CITIES[Math.floor(Math.random() * CITIES.length)];
    const moviesRes = http.get(`${BASE_URL}/api/cities/${city}/movies`);
    check(moviesRes, { 'movies 200': (r) => r.status === 200 });
    const movies = moviesRes.json();
    if (!movies.length) return;

    const movie = movies[Math.floor(Math.random() * movies.length)];
    const groups = http.get(`${BASE_URL}/api/cities/${city}/movies/${movie.id}/shows`).json();
    if (!groups.length) return;

    const show = groups[0].shows[0];
    check(http.get(`${BASE_URL}/api/shows/${show.id}`), {
      'show detail 200': (r) => r.status === 200,
      'has seat layout': (r) => Array.isArray(r.json().seats),
    });
  });

  sleep(Math.random() * 1 + 0.5); // think time
}
