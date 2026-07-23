import { useEffect, useMemo, useRef, useState } from 'react';
import { getShow, hold as holdSeats } from '../api.js';
import { holderId } from '../holder.js';

const TIER_LABEL = { REGULAR: 'Regular', PREMIUM: 'Premium', RECLINER: 'Recliner' };
const TIER_AVAILABLE = {
  REGULAR: 'border-slate-500/50 bg-slate-700/40 hover:bg-emerald-500/30 hover:border-emerald-400',
  PREMIUM: 'border-indigo-500/40 bg-indigo-500/15 hover:bg-emerald-500/30 hover:border-emerald-400',
  RECLINER: 'border-amber-500/40 bg-amber-500/15 hover:bg-emerald-500/30 hover:border-emerald-400',
};

const rupees = (n) => `₹${n.toLocaleString('en-IN')}`;

export default function SeatMap({ show, movie, onHeld }) {
  const [detail, setDetail] = useState(null);
  const [booked, setBooked] = useState(new Set());
  const [selected, setSelected] = useState(new Set());
  const [live, setLive] = useState(false);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const selectedRef = useRef(selected);
  selectedRef.current = selected;

  // Load the seat layout + current bookings.
  useEffect(() => {
    let active = true;
    getShow(show.id).then((d) => {
      if (!active) return;
      setDetail(d);
      setBooked(new Set(d.bookedSeats));
    });
    return () => {
      active = false;
    };
  }, [show.id]);

  // Subscribe to live seat updates over SSE.
  useEffect(() => {
    const es = new EventSource(`/api/shows/${show.id}/stream`);
    es.onopen = () => setLive(true);
    es.onerror = () => setLive(false);
    es.addEventListener('seats-update', (e) => {
      const data = JSON.parse(e.data);
      const nextBooked = new Set(data.booked);
      setBooked(nextBooked);
      // Drop any of my in-progress picks that someone else just grabbed.
      const mine = selectedRef.current;
      const survivors = new Set([...mine].filter((s) => !nextBooked.has(s)));
      if (survivors.size !== mine.size) {
        setSelected(survivors);
        setError('Some of your seats were just taken and have been removed.');
      }
    });
    return () => es.close();
  }, [show.id]);

  const seatsByRow = useMemo(() => {
    if (!detail) return [];
    const rows = new Map();
    for (const seat of detail.seats) {
      if (!rows.has(seat.row)) rows.set(seat.row, []);
      rows.get(seat.row).push(seat);
    }
    return [...rows.entries()].map(([row, seats]) => [row, seats.sort((a, b) => a.number - b.number)]);
  }, [detail]);

  const priceById = useMemo(() => {
    const m = new Map();
    detail?.seats.forEach((s) => m.set(s.id, s.price));
    return m;
  }, [detail]);

  const total = [...selected].reduce((sum, id) => sum + (priceById.get(id) || 0), 0);

  const toggle = (seat) => {
    if (booked.has(seat.id)) return;
    setError(null);
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(seat.id)) next.delete(seat.id);
      else next.add(seat.id);
      return next;
    });
  };

  const proceed = async () => {
    if (selected.size === 0) return;
    setSubmitting(true);
    setError(null);
    try {
      const seatIds = [...selected];
      const { ok, data } = await holdSeats(show.id, seatIds, holderId());
      if (ok && data.status === 'HELD') {
        // Hand the hold to the payment screen. Note: this does NOT mark the
        // seats booked for other users — only a confirmed booking does that.
        onHeld({
          holdId: data.holdId,
          seats: data.seats,
          ttlSeconds: data.ttlSeconds,
          totalPrice: data.totalPrice,
        });
      } else {
        setError(data.reason || 'Those seats are no longer available.');
        if (data.conflictingSeats?.length) {
          setSelected((prev) => {
            const next = new Set(prev);
            data.conflictingSeats.forEach((s) => next.delete(s));
            return next;
          });
        }
      }
    } catch {
      setError('Could not reach the booking service.');
    } finally {
      setSubmitting(false);
    }
  };

  if (!detail) return <p className="text-slate-500">Loading seat map…</p>;

  return (
    <section>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h1 className="text-2xl font-bold">{movie.title}</h1>
          <p className="text-sm text-slate-400">
            {detail.theatre.name} · {show.screen} · {show.startTime} · {show.format}
          </p>
        </div>
        <span
          className={
            'text-xs px-2.5 py-1 rounded-full border flex items-center gap-1.5 ' +
            (live
              ? 'border-emerald-500/40 text-emerald-300 bg-emerald-500/10'
              : 'border-slate-600 text-slate-400')
          }
        >
          <span className={'w-2 h-2 rounded-full ' + (live ? 'bg-emerald-400 animate-pulse' : 'bg-slate-500')} />
          {live ? 'Live' : 'Offline'}
        </span>
      </div>

      {/* Screen */}
      <div className="mb-6">
        <div className="h-2 rounded-full bg-gradient-to-r from-transparent via-slate-300/70 to-transparent" />
        <p className="text-center text-[11px] tracking-[0.3em] text-slate-500 mt-2">SCREEN</p>
      </div>

      {/* Legend */}
      <div className="flex flex-wrap gap-4 justify-center mb-6 text-xs text-slate-400">
        <Legend swatch="bg-slate-700/40 border-slate-500/50" label="Regular ₹180" />
        <Legend swatch="bg-indigo-500/15 border-indigo-500/40" label="Premium ₹260" />
        <Legend swatch="bg-amber-500/15 border-amber-500/40" label="Recliner ₹420" />
        <Legend swatch="bg-emerald-500 border-emerald-400" label="Selected" />
        <Legend swatch="bg-red-900/40 border-red-900/60" label="Booked" />
      </div>

      {/* Seat grid */}
      <div className="overflow-x-auto pb-2">
        <div className="inline-block min-w-full">
          {seatsByRow.map(([row, seats]) => (
            <div key={row} className="flex items-center gap-1.5 mb-1.5 justify-center">
              <span className="w-5 text-xs text-slate-500 text-right mr-1">{row}</span>
              {seats.map((seat) => {
                const isBooked = booked.has(seat.id);
                const isSelected = selected.has(seat.id);
                let cls = TIER_AVAILABLE[seat.tier];
                if (isBooked) cls = 'border-red-900/60 bg-red-900/40 text-red-300/40 cursor-not-allowed';
                else if (isSelected) cls = 'border-emerald-400 bg-emerald-500 text-ink font-bold';
                return (
                  <button
                    key={seat.id}
                    title={`${seat.id} · ${TIER_LABEL[seat.tier]} · ${rupees(seat.price)}`}
                    onClick={() => toggle(seat)}
                    disabled={isBooked}
                    className={'w-7 h-7 rounded-md border text-[10px] transition ' + cls}
                  >
                    {seat.number}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
      </div>

      {/* Summary / checkout bar */}
      <div className="mt-8 rounded-2xl border border-edge bg-panel/80 p-5 sticky bottom-4">
        {error && (
          <div className="mb-3 text-sm text-amber-300 bg-amber-500/10 border border-amber-500/30 rounded-lg px-3 py-2">
            {error}
          </div>
        )}
        <div className="flex flex-col sm:flex-row sm:items-center gap-4">
          <div className="flex-1">
            <div className="text-sm text-slate-400">
              {selected.size === 0
                ? 'No seats selected'
                : `${selected.size} seat${selected.size > 1 ? 's' : ''}: ${[...selected].sort().join(', ')}`}
            </div>
            <div className="text-2xl font-bold">{rupees(total)}</div>
          </div>
          <button
            onClick={proceed}
            disabled={selected.size === 0 || submitting}
            className="rounded-lg px-6 py-2.5 font-semibold bg-gradient-to-r from-indigo-500 to-amber-500 text-white disabled:opacity-40 disabled:cursor-not-allowed hover:brightness-110 transition"
          >
            {submitting ? 'Holding…' : 'Proceed to Pay'}
          </button>
        </div>
      </div>
    </section>
  );
}

function Legend({ swatch, label }) {
  return (
    <span className="flex items-center gap-1.5">
      <span className={'w-4 h-4 rounded border ' + swatch} />
      {label}
    </span>
  );
}
