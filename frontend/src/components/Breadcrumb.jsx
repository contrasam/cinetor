// Compact breadcrumb / step indicator shown above each screen.
export default function Breadcrumb({ city, movie, show, step, onHome, onCity, onMovie }) {
  const crumb = (label, active, onClick) => (
    <button
      onClick={onClick}
      disabled={!onClick}
      className={
        'truncate max-w-[10rem] ' +
        (active ? 'text-amber-300 font-semibold' : 'text-slate-400 hover:text-slate-200')
      }
    >
      {label}
    </button>
  );

  const sep = <span className="text-slate-600">/</span>;

  return (
    <nav className="flex items-center gap-2 text-sm mb-6 flex-wrap">
      {crumb('Cities', step === 'city', step === 'city' ? null : onHome)}
      {city && sep}
      {city && crumb(city.name, step === 'movies', step === 'movies' ? null : onCity)}
      {movie && sep}
      {movie && crumb(movie.title, step === 'shows', step === 'shows' ? null : onMovie)}
      {show && sep}
      {show && crumb(`${show.startTime} · ${show.format}`, step === 'seats' || step === 'confirm', null)}
    </nav>
  );
}
