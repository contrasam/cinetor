# 🎬 Cinetor

A small **cinema ticket-booking** demo that shows how an **actor system** ([Cajun](https://github.com/CajunSystems/cajun)) can power a real backend.

Browse movies by city → pick a theatre and showtime → choose seats on a live seat map → a no-op "payment" runs → you get a booking confirmation. While you're picking seats, **any seat booked by someone else lights up in real time** thanks to Server-Sent Events (SSE).

```
City  ─▶  Movies  ─▶  Theatre + Showtimes  ─▶  Seat map (live)  ─▶  Confirmation
```

---

## Why actors?

Seat booking is a textbook concurrency problem: two people must never book the same seat. Cinetor solves this **without locks** by giving every show its own actor.

- There is exactly **one `ShowActor` per screening**. It owns that show's set of booked seats as private state.
- A Cajun actor processes its mailbox **one message at a time**, so every read (`GetSnapshot`) and every write (`Book`) for a show is serialised through that single actor. Concurrent booking requests are queued and handled sequentially — a double-booking is simply impossible.
- The web layer talks to actors with the **ask pattern** (`system.ask(pid, message, timeout)`) and waits for the actor's reply.
- When a booking is confirmed, the API broadcasts the new seat state to every browser watching that show over **SSE**, so seat maps update live.

```
Browser ──HTTP──▶ Javalin route ──ask──▶ ShowActor (serialised seat state)
   ▲                   │  confirmed
   └───────SSE─────────┘  broadcast booked seats to all viewers
```

---

## Tech stack

| Layer     | Tech                                                             |
|-----------|-----------------------------------------------------------------|
| Backend   | Java 21, [Javalin 6](https://javalin.io), [Cajun](https://central.sonatype.com/artifact/com.cajunsystems/cajun) actor system, Jackson |
| Frontend  | React 18, Vite, Tailwind CSS                                     |
| Realtime  | Server-Sent Events (`EventSource`)                              |

---

## Project layout

```
cinetor/
├── backend/                      # Javalin + Cajun API (Gradle)
│   └── src/main/java/io/cinetor/
│       ├── CinetorApp.java       # HTTP routes, SSE endpoint, wiring
│       ├── actor/                # ShowActor + message protocol
│       ├── booking/              # BookingService (HTTP <-> actors, ask pattern)
│       ├── catalog/              # In-memory demo catalogue
│       ├── model/                # City/Movie/Theatre/Show + seat layout
│       ├── sse/                  # SeatStreamHub (SSE fan-out)
│       └── web/                  # Request/response DTOs
└── frontend/                     # React + Tailwind (Vite)
    └── src/
        ├── App.jsx               # Step flow + state
        ├── api.js                # fetch helpers
        └── components/           # CityGrid, MovieGrid, ShowtimeList, SeatMap, …
```

---

## Running it

You need **JDK 21** and **Node 18+**.

### 1. Start the backend (port 7070)

```bash
cd backend
./gradlew run          # or: gradle run
```

Cajun is pulled from Maven Central (`com.cajunsystems:cajun:0.7.0`). Its published
classes use Java 21 preview features, so the app is launched with
`--enable-preview` (already configured in `build.gradle`).

Override the port with `-Pport=8080` or the `PORT` env var.

### 2. Start the frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. The Vite dev server proxies `/api` (including the
SSE stream) to the backend on port 7070.

### See the live updates

Open the same showtime's seat map in **two browser windows**. Book a seat in one —
it turns red in the other instantly, with no refresh.

---

## HTTP API

| Method | Path                                             | Description                          |
|--------|--------------------------------------------------|--------------------------------------|
| GET    | `/api/cities`                                    | List cities                          |
| GET    | `/api/cities/{cityId}/movies`                    | Movies showing in a city             |
| GET    | `/api/cities/{cityId}/movies/{movieId}/shows`    | Shows grouped by theatre             |
| GET    | `/api/shows/{showId}`                            | Show detail + seat layout + booked   |
| POST   | `/api/shows/{showId}/book`                       | Book seats → confirmation or 409     |
| GET    | `/api/shows/{showId}/stream`                     | SSE stream of `seats-update` events  |

**Book request**

```json
{ "seatIds": ["E5", "E6"], "customerName": "Ada" }
```

**Confirmed response**

```json
{
  "status": "CONFIRMED",
  "bookingId": "BK-1A2B3C4D",
  "customerName": "Ada",
  "seats": ["E5", "E6"],
  "totalPrice": 520,
  "bookedSeats": ["E5", "E6"]
}
```

If a seat was taken in the meantime the API returns **409** with
`status: "REJECTED"` and the `conflictingSeats`.

---

## Notes

- The catalogue (cities, movies, theatres, shows) is static seed data. The
  interesting, stateful part is the live seat booking handled by actors.
- Seat state is held **in memory** by each `ShowActor`, so restarting the backend
  starts every show fresh. (Cajun also supports persistent stateful actors; this
  demo intentionally keeps it in-memory.)
