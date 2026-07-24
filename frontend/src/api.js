// Thin fetch wrapper around the Cinetor backend. All calls go through the Vite
// dev proxy (or the same origin in production), so no base URL is needed.

async function json(res) {
  if (!res.ok && res.status !== 409) {
    throw new Error(`Request failed: ${res.status}`);
  }
  return res.json();
}

export const getConfig = () => fetch('/api/config').then(json);

export const getCities = () => fetch('/api/cities').then(json);

export const getMovies = (cityId) =>
  fetch(`/api/cities/${cityId}/movies`).then(json);

export const getShows = (cityId, movieId) =>
  fetch(`/api/cities/${cityId}/movies/${movieId}/shows`).then(json);

export const getShow = (showId) => fetch(`/api/shows/${showId}`).then(json);

// Phase 1: reserve seats for the payment window.
export async function hold(showId, seatIds, holderId) {
  const res = await fetch(`/api/shows/${showId}/hold`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seatIds, holderId }),
  });
  return { ok: res.ok, data: await json(res) };
}

// Phase 2: turn a hold into a booking.
export async function confirm(showId, holdId, holderId, customerName) {
  const res = await fetch(`/api/shows/${showId}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ holdId, holderId, customerName }),
  });
  return { ok: res.ok, data: await json(res) };
}

// Give up a hold early (user cancelled). Fire-and-forget.
export function release(showId, holdId, holderId) {
  return fetch(`/api/shows/${showId}/release`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ holdId, holderId }),
  }).catch(() => {});
}

// Best-effort release during page unload (refresh / tab close), where a normal
// fetch would be cancelled. sendBeacon queues the request so it still goes out.
// The server-side hold expiry remains the ultimate guarantee if this doesn't land.
export function releaseBeacon(showId, holdId, holderId) {
  try {
    if (!navigator.sendBeacon) {
      return;
    }
    const body = new Blob([JSON.stringify({ holdId, holderId })], {
      type: 'application/json',
    });
    navigator.sendBeacon(`/api/shows/${showId}/release`, body);
  } catch {
    /* best effort — the hold will expire server-side regardless */
  }
}
