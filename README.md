# 🎬 Cinetor

A small **cinema ticket-booking** demo that shows how an **actor system** ([Cajun](https://github.com/CajunSystems/cajun)) can power a real backend.

Browse movies by city → pick a theatre and showtime → choose seats on a live seat map → your seats are **held for 5 minutes** while a no-op "payment" runs → you get a booking confirmation. While you're picking seats, **any seat booked by someone else lights up in real time** thanks to Server-Sent Events (SSE).

```
City  ─▶  Movies  ─▶  Theatre + Showtimes  ─▶  Seat map (live)  ─▶  Payment (5-min hold)  ─▶  Confirmation
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

### Holds vs. bookings (the 5-minute block)

Booking is two-phase, and the two phases are treated very differently:

1. **Hold** — when you click *Proceed to Pay*, your seats are reserved for you
   for a hold window (default **5 minutes**). A held seat is **blocked**: no one
   else can hold or book it. But a hold is **deliberately not broadcast** — other
   viewers' seat maps do **not** change, and the held seats are **not** included
   in the public "booked" snapshot. The block only surfaces if another user
   actually tries to grab the same seat (they get a "no longer available"
   rejection).
2. **Confirm** — when payment completes, the hold becomes a real booking. **This
   is the only event that changes the public seat map**, so it is the only event
   that triggers an SSE broadcast.

If payment isn't completed in time, the hold **auto-releases**: the actor
schedules an `ExpireHold` message to itself (`context.tellSelf(msg, window,
MINUTES)`) when the hold is created, so the seats free up on their own with no
external scheduler. Cancelling on the payment screen releases the hold
immediately.

> In short: **holds block, bookings broadcast.** The live seat map only ever
> reflects confirmed bookings, never in-progress holds.

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

Override the port with `-Pport=8080` or the `PORT` env var. The 5-minute seat
hold can be shortened for demos with `-PholdSeconds=30` (or the `HOLD_SECONDS`
env var) — handy for watching a hold auto-release.

### 2. Start the frontend (port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**. The Vite dev server proxies `/api` (including the
SSE stream) to the backend on port 7070.

### See the live updates (and the hold behaviour)

Open the same showtime's seat map in **two browser windows** (each tab is a
separate "user"):

- In window A, select seats and click **Proceed to Pay**. Window B's seat map
  does **not** change — the seats are only *held*, not booked.
- Try to select the same seats in window B and proceed: you'll be told they're
  **no longer available**. The hold is blocking you, invisibly.
- Complete payment in window A. **Now** the seats turn red in window B, live.
- Or, on the payment screen, hit **Cancel** (or let the timer run out) and the
  seats free up again.

---

## HTTP API

| Method | Path                                             | Description                             |
|--------|--------------------------------------------------|-----------------------------------------|
| GET    | `/api/config`                                    | UI config (e.g. `holdSeconds`)          |
| GET    | `/api/cities`                                    | List cities                             |
| GET    | `/api/cities/{cityId}/movies`                    | Movies showing in a city                |
| GET    | `/api/cities/{cityId}/movies/{movieId}/shows`    | Shows grouped by theatre                |
| GET    | `/api/shows/{showId}`                            | Show detail + seat layout + booked      |
| POST   | `/api/shows/{showId}/hold`                       | **Phase 1** — hold seats → hold or 409  |
| POST   | `/api/shows/{showId}/confirm`                    | **Phase 2** — confirm hold → booking    |
| POST   | `/api/shows/{showId}/release`                    | Release a hold early (cancel)           |
| GET    | `/api/shows/{showId}/stream`                     | SSE stream of `seats-update` events     |
| GET    | `/api/health`                                    | Liveness probe (`{"status":"ok"}`)      |
| GET    | `/api/metrics`                                   | Prometheus metrics (Micrometer)         |

**Hold request** (`POST /hold`) — `holderId` identifies the user session:

```json
{ "seatIds": ["E5", "E6"], "holderId": "a1b2c3" }
```

**Held response** — `ttlSeconds` is how long the hold lasts:

```json
{ "status": "HELD", "holdId": "HOLD-1A2B3C4D", "seats": ["E5", "E6"], "ttlSeconds": 300, "totalPrice": 520 }
```

**Confirm request** (`POST /confirm`):

```json
{ "holdId": "HOLD-1A2B3C4D", "holderId": "a1b2c3", "customerName": "Ada" }
```

**Confirmed response**:

```json
{ "status": "CONFIRMED", "bookingId": "BK-1A2B3C4D", "customerName": "Ada", "seats": ["E5", "E6"], "totalPrice": 520, "bookedSeats": ["E5", "E6"] }
```

If a seat is unavailable (booked, or held by someone else), `/hold` returns
**409** with `status: "REJECTED"` and the `conflictingSeats`. If a hold has
expired by the time you pay, `/confirm` returns **409** as well.

---

## Notes

- The catalogue (cities, movies, theatres, shows) is static seed data. The
  interesting, stateful part is the live seat booking handled by actors.
- Seat state (bookings and holds) is held **in memory** by each `ShowActor`, so
  restarting the backend starts every show fresh. (Cajun also supports persistent
  stateful actors; this demo intentionally keeps it in-memory.)
- Holds auto-expire via a self-scheduled actor message, so no seat stays blocked
  forever even if a browser is closed mid-payment.

---

## Booking lifecycle & edge cases

The full lifecycle of a seat, and how each tricky case is handled. The
authority for a show's seat state is always its single `ShowActor` — because it
processes one message at a time, every rule below is enforced without locks.

### Lifecycle of a seat

```
available ──hold──▶ held (blocked, invisible to others' maps)
   ▲                  │
   │                  ├── confirm ─▶ booked  ──▶ SSE broadcast to all viewers
   └── release / expiry ┘            (permanent)
```

A hold is a private, time-boxed reservation; a booking is the permanent, public
result. The public seat map (REST snapshot + SSE) only ever reflects **booked**
seats, never held ones.

### How each case is covered

| Case | Behaviour | Where |
| --- | --- | --- |
| **Two users book the same seat at once** | Impossible. Both requests are serialised through the one `ShowActor`; the second sees the seat taken and is rejected. | `ShowActor` (single-threaded mailbox) |
| **A user holds seats during payment** | Seats are blocked for others but **not** broadcast — other maps don't change, and the held seats are absent from the booked snapshot. | `ShowActor.handleHold` (no broadcast); `CinetorApp` broadcasts only on confirm |
| **Someone else tries to grab held seats** | Rejected with `409` + `conflictingSeats`; the block only surfaces on the attempt, never on the live map. | `ShowActor.handleHold` |
| **Duplicate seat ids in one request** | Collapsed to a distinct set, so a seat can't be held or charged twice. | `ShowActor.handleHold` (`LinkedHashSet`) |
| **Payment not completed in time** | The hold auto-expires and the seats free up, via a self-scheduled `ExpireHold` message (plus a lazy purge as a safety net). | `ShowActor` (`context.tellSelf(..., holdWindow)`) |
| **User cancels on the payment screen** | Hold released immediately (`POST /release`); seats become available at once. | `Payment.cancel` → `release` |
| **User navigates away in-app (logo / breadcrumb)** | Hold released immediately — navigation calls the release API before clearing state. | `App.dropHold` |
| **User refreshes or closes the tab** | Best-effort release via `navigator.sendBeacon` on `pagehide` (a normal `fetch` would be cancelled). If it doesn't land, the server-side expiry still frees the seat. | `Payment` `pagehide` → `releaseBeacon` |
| **Page restored from back-forward cache** | The beacon is skipped when `event.persisted` is true, so a restored page keeps its hold and can still pay. The countdown is derived from a fixed deadline, so it shows the true remaining time after a freeze/thaw instead of drifting. | `Payment` (`!event.persisted`, deadline-based timer) |
| **Confirming an expired / released / foreign hold** | Controlled `409` rejection ("hold expired" / "belongs to another session"); never a crash or timeout. | `ShowActor.handleConfirm` (`Objects.equals`) |
| **Request omits `holderId`** | Rejected up front with a controlled `409` "Missing holder id"; a null owner is never stored, so confirm/release can't NPE. | `ShowActor.handleHold` |
| **A seat you selected gets booked mid-selection** | The SSE update drops it from your selection and shows a notice, so you can't try to pay for a taken seat. | `SeatMap` seats-update handler |
| **SSE update arrives before the initial REST load** | The live update wins; a late REST snapshot can't overwrite newer booked state (guarded by a `liveSeen` ref). | `SeatMap` |
| **Backend restart** | State is in-memory, so every show starts fresh (no stale holds or bookings). | `ShowActor` |

### The one deliberate trade-off

`sendBeacon` on unload and the in-app release are best-effort — a crash, a killed
process, or lost connectivity can skip them. That's intentional: **the
server-side hold expiry is the single source of truth for cleanup.** Client-side
release just shortens the common case from "up to the hold window" to "instant";
it is never relied upon for correctness.
