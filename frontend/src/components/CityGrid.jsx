import { useEffect, useState } from 'react';
import { getCities } from '../api.js';

export default function CityGrid({ onPick }) {
  const [cities, setCities] = useState(null);

  useEffect(() => {
    getCities().then(setCities).catch(() => setCities([]));
  }, []);

  return (
    <section>
      <h1 className="text-3xl font-bold mb-1">Where are you watching?</h1>
      <p className="text-slate-400 mb-6">Pick your city to see what's showing today.</p>

      {!cities && <p className="text-slate-500">Loading cities…</p>}

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        {cities?.map((city) => (
          <button
            key={city.id}
            onClick={() => onPick(city)}
            className="group rounded-2xl border border-edge bg-panel/70 p-6 text-left hover:border-indigo-400/60 hover:bg-panel transition"
          >
            <div className="text-4xl mb-3">📍</div>
            <div className="text-xl font-semibold group-hover:text-indigo-300">{city.name}</div>
            <div className="text-sm text-slate-500 mt-1">Browse movies →</div>
          </button>
        ))}
      </div>
    </section>
  );
}
