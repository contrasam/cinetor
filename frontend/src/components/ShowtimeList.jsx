import { useEffect, useState } from 'react';
import { getShows } from '../api.js';

export default function ShowtimeList({ city, movie, onPick }) {
  const [groups, setGroups] = useState(null);

  useEffect(() => {
    setGroups(null);
    getShows(city.id, movie.id).then(setGroups).catch(() => setGroups([]));
  }, [city.id, movie.id]);

  return (
    <section>
      <h1 className="text-3xl font-bold mb-1">{movie.title}</h1>
      <p className="text-slate-400 mb-6">
        {movie.genre} · {movie.rating} · {city.name}
      </p>

      {!groups && <p className="text-slate-500">Loading showtimes…</p>}

      <div className="space-y-4">
        {groups?.map(({ theatre, shows }) => (
          <div key={theatre.id} className="rounded-2xl border border-edge bg-panel/70 p-5">
            <div className="flex items-baseline justify-between mb-3">
              <div>
                <div className="text-lg font-semibold">{theatre.name}</div>
                <div className="text-xs text-slate-500">{theatre.area}</div>
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              {shows.map((show) => (
                <button
                  key={show.id}
                  onClick={() => onPick(show)}
                  className="rounded-lg border border-edge bg-ink/60 px-4 py-2 hover:border-emerald-400/70 hover:text-emerald-300 transition text-sm font-medium"
                >
                  <span>{show.startTime}</span>
                  <span className="ml-2 text-[10px] uppercase tracking-wide text-slate-500">
                    {show.format}
                  </span>
                </button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
