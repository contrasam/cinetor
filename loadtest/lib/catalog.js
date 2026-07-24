// Catalogue + seat helpers shared by the scenarios.

import http from 'k6/http';
import { BASE_URL } from './config.js';

// Walk the public browse hierarchy (city -> movies -> shows) to discover every
// bookable show and its seat-grid size. There is no "list all shows" endpoint,
// so this mirrors what a real client does. Returns [{ id, rows, cols }, ...].
export function discoverShows(baseUrl = BASE_URL) {
  const out = [];
  const cities = http.get(`${baseUrl}/api/cities`).json();
  for (const city of cities) {
    const movies = http.get(`${baseUrl}/api/cities/${city.id}/movies`).json();
    for (const movie of movies) {
      const groups = http.get(`${baseUrl}/api/cities/${city.id}/movies/${movie.id}/shows`).json();
      for (const g of groups) {
        for (const s of g.shows) {
          out.push({ id: s.id, rows: s.rows, cols: s.cols });
        }
      }
    }
  }
  return out;
}

// Backend seat-id scheme (see model/Seats.java): row letter (A, B, ...) from the
// screen backwards, then column number 1..cols. e.g. a 10x14 hall is A1..J14.
export function allSeatIds(rows, cols) {
  const ids = [];
  for (let r = 0; r < rows; r++) {
    const rowLabel = String.fromCharCode(65 + r);
    for (let c = 1; c <= cols; c++) ids.push(rowLabel + c);
  }
  return ids;
}

// Seats not currently booked, per the public snapshot. Held-but-unconfirmed
// seats are deliberately NOT in the snapshot, so a hold here may still 409 —
// that is expected and the scenarios treat it as a normal outcome.
export function availableSeats(showId, baseUrl = BASE_URL) {
  const detail = http.get(`${baseUrl}/api/shows/${showId}`).json();
  const booked = new Set(detail.bookedSeats || []);
  return allSeatIds(detail.rows, detail.cols).filter((s) => !booked.has(s));
}

// Random distinct sample of n items.
export function pickN(arr, n) {
  const copy = arr.slice();
  const out = [];
  for (let i = 0; i < n && copy.length; i++) {
    const idx = Math.floor(Math.random() * copy.length);
    out.push(copy.splice(idx, 1)[0]);
  }
  return out;
}
