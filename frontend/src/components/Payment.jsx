import { useEffect, useState } from 'react';
import { confirm as confirmBooking, release } from '../api.js';
import { holderId } from '../holder.js';

const rupees = (n) => `₹${n.toLocaleString('en-IN')}`;
const mmss = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;

/**
 * Payment screen shown while the selected seats are held. The seats are blocked
 * for other users for the hold window (shown as a live countdown). If the timer
 * runs out — or the user cancels — the hold is released and the seats free up
 * again. Nothing here changes the public seat map; only a confirmed payment does.
 */
export default function Payment({ show, movie, hold, onConfirmed, onCancel }) {
  const [remaining, setRemaining] = useState(hold.ttlSeconds);
  const [name, setName] = useState('');
  const [error, setError] = useState(null);
  const [paying, setPaying] = useState(false);
  const [expired, setExpired] = useState(false);

  // Countdown. When it hits zero the backend has already auto-released the hold.
  // (Navigating away without cancelling also lets the hold expire server-side
  // after the hold window — no seat stays blocked forever.)
  useEffect(() => {
    if (remaining <= 0) {
      setExpired(true);
      return;
    }
    const t = setTimeout(() => setRemaining((r) => r - 1), 1000);
    return () => clearTimeout(t);
  }, [remaining]);

  const pay = async () => {
    setPaying(true);
    setError(null);
    try {
      const { ok, data } = await confirmBooking(
        show.id,
        hold.holdId,
        holderId(),
        name.trim() || 'Guest',
      );
      if (ok && data.status === 'CONFIRMED') {
        onConfirmed(data);
      } else {
        setError(data.reason || 'Payment could not be completed.');
        setExpired(true);
      }
    } catch {
      setError('Could not reach the booking service.');
    } finally {
      setPaying(false);
    }
  };

  const cancel = () => {
    release(show.id, hold.holdId, holderId());
    onCancel();
  };

  const low = remaining <= 30;

  return (
    <section className="max-w-md mx-auto">
      <div className="rounded-2xl border border-edge bg-panel/80 p-6">
        <div className="flex items-center justify-between mb-1">
          <h1 className="text-2xl font-bold">Payment</h1>
          <span
            className={
              'text-sm font-mono px-2.5 py-1 rounded-full border ' +
              (expired
                ? 'border-red-500/40 text-red-300 bg-red-500/10'
                : low
                  ? 'border-amber-500/50 text-amber-300 bg-amber-500/10'
                  : 'border-emerald-500/40 text-emerald-300 bg-emerald-500/10')
            }
          >
            {expired ? 'expired' : `⏱ ${mmss(remaining)}`}
          </span>
        </div>
        <p className="text-sm text-slate-400">
          {movie.title} · {show.startTime} · {show.format}
        </p>

        <div className="mt-5 rounded-xl border border-edge bg-ink/50 p-4 text-sm space-y-2">
          <div className="flex justify-between">
            <span className="text-slate-500">Seats held</span>
            <span className="font-medium">{hold.seats.slice().sort().join(', ')}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-slate-500">Amount</span>
            <span className="text-xl font-bold">{rupees(hold.totalPrice)}</span>
          </div>
        </div>

        <p className="text-xs text-slate-500 mt-3">
          These seats are held just for you until the timer runs out. Other guests
          can't book them meanwhile — but they'll only see them taken once you pay.
        </p>

        {error && (
          <div className="mt-4 text-sm text-amber-300 bg-amber-500/10 border border-amber-500/30 rounded-lg px-3 py-2">
            {error}
          </div>
        )}

        {expired ? (
          <button
            onClick={cancel}
            className="mt-5 w-full rounded-lg px-6 py-2.5 font-semibold border border-edge hover:border-slate-400 transition"
          >
            Back to seat map
          </button>
        ) : (
          <div className="mt-5 space-y-3">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Name on booking"
              className="w-full rounded-lg bg-ink/70 border border-edge px-3 py-2 text-sm outline-none focus:border-indigo-400"
            />
            <div className="flex gap-3">
              <button
                onClick={cancel}
                disabled={paying}
                className="flex-1 rounded-lg px-4 py-2.5 font-semibold border border-edge hover:border-slate-400 transition disabled:opacity-40"
              >
                Cancel
              </button>
              <button
                onClick={pay}
                disabled={paying}
                className="flex-[2] rounded-lg px-6 py-2.5 font-semibold bg-gradient-to-r from-indigo-500 to-amber-500 text-white hover:brightness-110 transition disabled:opacity-40"
              >
                {paying ? 'Processing…' : `Pay ${rupees(hold.totalPrice)}`}
              </button>
            </div>
            <p className="text-center text-[11px] text-slate-600">
              This is a demo — no real payment is taken.
            </p>
          </div>
        )}
      </div>
    </section>
  );
}
