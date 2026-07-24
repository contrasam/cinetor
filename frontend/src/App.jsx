import { useState } from 'react';
import CityGrid from './components/CityGrid.jsx';
import MovieGrid from './components/MovieGrid.jsx';
import ShowtimeList from './components/ShowtimeList.jsx';
import SeatMap from './components/SeatMap.jsx';
import Payment from './components/Payment.jsx';
import Confirmation from './components/Confirmation.jsx';
import Breadcrumb from './components/Breadcrumb.jsx';
import { release } from './api.js';
import { holderId } from './holder.js';

// Simple step-driven flow: city -> movie -> showtime -> seats -> confirmation.
// State is kept here and threaded down; no router needed for a demo this size.
export default function App() {
  const [city, setCity] = useState(null);
  const [movie, setMovie] = useState(null);
  const [show, setShow] = useState(null);
  const [hold, setHold] = useState(null);
  const [confirmation, setConfirmation] = useState(null);

  // Navigating away from an unpaid hold must give the seats back immediately,
  // rather than leaving them blocked until the server-side hold expires.
  const dropHold = () => {
    if (show && hold) {
      release(show.id, hold.holdId, holderId());
    }
    setHold(null);
  };

  const reset = () => {
    dropHold();
    setCity(null);
    setMovie(null);
    setShow(null);
    setConfirmation(null);
  };

  let step = 'city';
  if (confirmation) step = 'confirm';
  else if (hold) step = 'payment';
  else if (show) step = 'seats';
  else if (movie) step = 'shows';
  else if (city) step = 'movies';

  return (
    <div className="min-h-screen">
      <header className="border-b border-edge/60 backdrop-blur sticky top-0 z-10 bg-ink/70">
        <div className="max-w-5xl mx-auto px-4 py-4 flex items-center gap-3">
          <button onClick={reset} className="flex items-center gap-2 group">
            <span className="text-2xl">🎬</span>
            <span className="text-2xl font-black tracking-tight bg-gradient-to-r from-indigo-400 to-amber-400 bg-clip-text text-transparent">
              Cinetor
            </span>
          </button>
          <span className="text-xs text-slate-500 ml-auto hidden sm:block">
            Actor-powered live seat booking
          </span>
        </div>
      </header>

      <main className="max-w-5xl mx-auto px-4 py-6">
        <Breadcrumb
          city={city}
          movie={movie}
          show={show}
          step={step}
          onHome={reset}
          onCity={() => {
            dropHold();
            setMovie(null);
            setShow(null);
            setConfirmation(null);
          }}
          onMovie={() => {
            dropHold();
            setShow(null);
            setConfirmation(null);
          }}
        />

        {step === 'city' && <CityGrid onPick={setCity} />}

        {step === 'movies' && (
          <MovieGrid city={city} onPick={setMovie} />
        )}

        {step === 'shows' && (
          <ShowtimeList city={city} movie={movie} onPick={setShow} />
        )}

        {step === 'seats' && (
          <SeatMap
            show={show}
            movie={movie}
            onHeld={setHold}
          />
        )}

        {step === 'payment' && (
          <Payment
            show={show}
            movie={movie}
            hold={hold}
            onConfirmed={(c) => {
              // Hold is now a booking; drop it so we never try to release it.
              setHold(null);
              setConfirmation(c);
            }}
            onCancel={() => setHold(null)}
          />
        )}

        {step === 'confirm' && (
          <Confirmation
            confirmation={confirmation}
            show={show}
            movie={movie}
            city={city}
            onDone={reset}
          />
        )}
      </main>

      <footer className="max-w-5xl mx-auto px-4 py-8 text-center text-xs text-slate-600">
        Cinetor · React + Tailwind front end · Javalin + Cajun actor backend
      </footer>
    </div>
  );
}
