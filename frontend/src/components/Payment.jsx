import { useEffect, useState } from 'react';
import { confirm as confirmBooking, release, releaseBeacon } from '../api.js';
import { holderId } from '../holder.js';

const rupees = (n) => `₹${n.toLocaleString('en-IN')}`;
const mmss = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
const secondsUntil = (deadline) => Math.max(0, Math.ceil((deadline - Date.now()) / 1000));

/**
 * Payment screen shown while the selected seats are held. The seats are blocked
 * for other users for the hold window (shown as a live countdown). If the timer
 * runs out — or the user cancels — the hold is released and the seats free up
 * again. Nothing here changes the public seat map; only a confirmed payment does.
 */
export default function Payment({ show, movie, hold, onConfirmed, onCancel }) {
  const [name, setName] = useState('');
  const [error, setError] = useState(null);
  const [paying, setPaying] = useState(false);
  const [rejected, setRejected] = useState(false);

  // Count down against a fixed wall-clock deadline (client clock, so no server
  // skew) rather than a decrementing counter, recomputing from Date.now() each
  // tick. This self-corrects if the tab is frozen and thawed — e.g. restored
  // from the back-forward cache — instead of drifting.
  const [deadline] = useState(() => Date.now() + hold.ttlSeconds * 1000);
  const [remaining, setRemaining] = useState(() => secondsUntil(deadline));
  useEffect(() => {
    const tick = () => setRemaining(secondsUntil(deadline));
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [deadline]);

  const timedOut = remaining <= 0;
  const over = timedOut || rejected;
  const low = remaining <= 30 && !over;

  // A refresh or tab close during payment can't run React callbacks, so release
  // the hold with a beacon on unload. Skip it when the page is only being stashed
  // in the back-forward cache (event.persisted): the hold must survive so the
  // restored page can still pay. `pagehide` (not `visibilitychange`) means simply
  // switching tabs never drops the seats.
  useEffect(() => {
    const onExit = (event) => {
      if (!event.persisted) {
        releaseBeacon(show.id, hold.holdId, holderId());
      }
    };
    window.addEventListener('pagehide', onExit);
    return () => window.removeEventListener('pagehide', onExit);
  }, [show.id, hold.holdId]);

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
        setRejected(true);
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

  return (
    <section className="max-w-md mx-auto">
      <div className="rounded-2xl border border-edge bg-panel/80 p-6">
        <div className="flex items-center justify-between mb-1">
          <h1 className="text-2xl font-bold">Payment</h1>
          <span
            className={
              'text-sm font-mono px-2.5 py-1 rounded-full border ' +
              (over
                ? 'border-red-500/40 text-red-300 bg-red-500/10'
                : low
                  ? 'border-amber-500/50 text-amber-300 bg-amber-500/10'
                  : 'border-emerald-500/40 text-emerald-300 bg-emerald-500/10')
            }
          >
            {over ? 'expired' : `⏱ ${mmss(remaining)}`}
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

        {over ? (
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
