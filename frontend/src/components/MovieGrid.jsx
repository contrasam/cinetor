import { useEffect, useState } from 'react';
import { getMovies } from '../api.js';

export default function MovieGrid({ city, onPick }) {
  const [movies, setMovies] = useState(null);

  useEffect(() => {
    setMovies(null);
    getMovies(city.id).then(setMovies).catch(() => setMovies([]));
  }, [city.id]);

  return (
    <section>
      <h1 className="text-3xl font-bold mb-1">Now showing in {city.name}</h1>
      <p className="text-slate-400 mb-6">Choose a movie to see showtimes.</p>

      {!movies && <p className="text-slate-500">Loading movies…</p>}
      {movies?.length === 0 && <p className="text-slate-500">No movies found.</p>}

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4">
        {movies?.map((movie) => (
          <button
            key={movie.id}
            onClick={() => onPick(movie)}
            className="group rounded-2xl overflow-hidden border border-edge bg-panel/70 text-left hover:border-amber-400/60 transition"
          >
            <div
              className="aspect-[2/3] flex items-end p-3 relative"
              style={{
                background: `linear-gradient(160deg, ${movie.posterColor} 0%, rgba(11,11,18,0.9) 90%)`,
              }}
            >
              <span className="absolute top-2 right-2 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-black/40 border border-white/10">
                {movie.rating}
              </span>
              <span className="text-lg font-bold leading-tight drop-shadow">{movie.title}</span>
            </div>
            <div className="p-3">
              <div className="text-xs text-slate-400">
                {movie.genre} · {movie.language}
              </div>
              <div className="text-xs text-slate-500 mt-0.5">
                {Math.floor(movie.runtimeMinutes / 60)}h {movie.runtimeMinutes % 60}m
              </div>
            </div>
          </button>
        ))}
      </div>
    </section>
  );
}
