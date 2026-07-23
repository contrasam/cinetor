// Thin fetch wrapper around the Cinetor backend. All calls go through the Vite
// dev proxy (or the same origin in production), so no base URL is needed.

async function json(res) {
  if (!res.ok && res.status !== 409) {
    throw new Error(`Request failed: ${res.status}`);
  }
  return res.json();
}

export const getCities = () => fetch('/api/cities').then(json);

export const getMovies = (cityId) =>
  fetch(`/api/cities/${cityId}/movies`).then(json);

export const getShows = (cityId, movieId) =>
  fetch(`/api/cities/${cityId}/movies/${movieId}/shows`).then(json);

export const getShow = (showId) => fetch(`/api/shows/${showId}`).then(json);

export async function book(showId, seatIds, customerName) {
  const res = await fetch(`/api/shows/${showId}/book`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seatIds, customerName }),
  });
  const data = await json(res);
  return { ok: res.ok, data };
}
