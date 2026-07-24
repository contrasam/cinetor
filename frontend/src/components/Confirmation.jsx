const rupees = (n) => `₹${n.toLocaleString('en-IN')}`;

export default function Confirmation({ confirmation, show, movie, city, onDone }) {
  const c = confirmation;
  return (
    <section className="max-w-md mx-auto text-center">
      <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/5 p-8">
        <div className="text-5xl mb-3">🎟️</div>
        <h1 className="text-2xl font-bold text-emerald-300">Booking confirmed!</h1>
        <p className="text-slate-400 text-sm mt-1">
          A noop payment of {rupees(c.totalPrice)} was processed.
        </p>

        <div className="mt-6 text-left rounded-xl border border-edge bg-panel/70 p-5 space-y-2 text-sm">
          <Row label="Booking ID" value={c.bookingId} mono />
          <Row label="Name" value={c.customerName} />
          <Row label="Movie" value={movie.title} />
          <Row label="Screen" value={`${show.screen} · ${show.format}`} />
          <Row label="City / Time" value={`${city.name} · ${show.startTime}`} />
          <Row label="Seats" value={c.seats.slice().sort().join(', ')} />
          <div className="border-t border-edge my-2" />
          <Row label="Total paid" value={rupees(c.totalPrice)} bold />
        </div>

        <button
          onClick={onDone}
          className="mt-6 rounded-lg px-6 py-2.5 font-semibold bg-gradient-to-r from-indigo-500 to-amber-500 text-white hover:brightness-110 transition"
        >
          Book another
        </button>
      </div>
    </section>
  );
}

function Row({ label, value, mono, bold }) {
  return (
    <div className="flex justify-between gap-4">
      <span className="text-slate-500">{label}</span>
      <span className={(mono ? 'font-mono ' : '') + (bold ? 'font-bold ' : '') + 'text-right'}>
        {value}
      </span>
    </div>
  );
}
