package io.cinetor;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.config.ThreadPoolFactory;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.PersistenceFactory;
import io.cinetor.actor.ActorMode;
import io.cinetor.actor.ShowProtocol;
import io.cinetor.actor.ShowState;
import io.cinetor.booking.BookingService;
import io.cinetor.catalog.CatalogueData;
import io.cinetor.metrics.Metrics;
import io.cinetor.model.Catalogue.Show;
import io.cinetor.model.Seats;
import io.cinetor.model.Seats.Seat;
import io.cinetor.sse.SeatStreamHub;
import io.cinetor.web.Dtos;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cinetor backend entry point.
 *
 * <p>A tiny Javalin web API in front of a Cajun actor system. The catalogue
 * (cities / movies / theatres / shows) is static demo data; live seat state for
 * every show is owned by a per-show actor. Booking goes HTTP -> BookingService
 * -> ShowActor (serialised) -> reply, and confirmed bookings are pushed to all
 * connected seat-map viewers over SSE.
 */
public final class CinetorApp {

    private static final Logger log = LoggerFactory.getLogger(CinetorApp.class);

    public static void main(String[] args) {
        int port = resolvePort();
        long holdMillis = resolveHoldMillis();
        ActorMode mode = resolveActorMode();

        // A backpressure-aware system needs a system-level monitor, which only
        // exists when the system is built with a BackpressureConfig. The other
        // modes use the plain no-arg system, exactly as before.
        ActorSystem system = mode.usesBackpressure()
                ? new ActorSystem(new ThreadPoolFactory(), BookingService.backpressureConfig())
                : new ActorSystem();

        CatalogueData catalogue = new CatalogueData();
        Metrics metrics = new Metrics();
        metrics.registerModeInfo(mode.label());

        // Stateful modes journal every command and snapshot state to disk. One
        // journal + snapshot store instance serves every show (each is keyed by
        // the actor id internally). Cajun's file stores root at ./cajun_persistence.
        BatchedMessageJournal<ShowProtocol.Command> journal = null;
        SnapshotStore<ShowState> snapshots = null;
        if (mode.isStateful()) {
            journal = PersistenceFactory.createBatchedFileMessageJournal();
            snapshots = PersistenceFactory.createFileSnapshotStore();
        }

        BookingService bookings = new BookingService(
                system, catalogue, holdMillis, metrics, mode, journal, snapshots);
        registerBackpressureMetrics(metrics, system, bookings);
        SeatStreamHub seatStream = new SeatStreamHub(metrics);

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
            config.showJavalinBanner = false;
        });

        // Time every request, tagged by matched route + status class, so per-endpoint
        // latency and throughput show up in /api/metrics alongside the actor timings.
        app.before(ctx -> ctx.attribute("startNanos", System.nanoTime()));
        app.after(ctx -> {
            Long start = ctx.attribute("startNanos");
            if (start == null) {
                return;
            }
            // The matched route template (e.g. /api/shows/{showId}); keeps label
            // cardinality bounded. Unmatched requests (404s) have no endpoint path.
            String route;
            try {
                route = ctx.endpointHandlerPath();
            } catch (Exception e) {
                route = "unmatched";
            }
            String statusClass = (ctx.statusCode() / 100) + "xx";
            metrics.httpTimer(ctx.method().name(), route, statusClass)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        });

        // --- Catalogue browsing -------------------------------------------------

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        // Prometheus scrape endpoint for the load-test / observability stack.
        app.get("/api/metrics", ctx ->
                ctx.contentType("text/plain; version=0.0.4; charset=utf-8").result(metrics.scrape()));

        // Lets the UI show the correct countdown length for held seats, and
        // reports which actor mode the backend is running in.
        app.get("/api/config", ctx ->
                ctx.json(Map.of(
                        "holdSeconds", (int) (holdMillis / 1000),
                        "actorMode", mode.label())));

        app.get("/api/cities", ctx -> ctx.json(catalogue.cities()));

        app.get("/api/cities/{cityId}/movies", ctx ->
                ctx.json(catalogue.moviesInCity(ctx.pathParam("cityId"))));

        app.get("/api/cities/{cityId}/movies/{movieId}/shows", ctx ->
                ctx.json(catalogue.theatreShows(ctx.pathParam("cityId"), ctx.pathParam("movieId"))));

        // --- Show detail + seat map --------------------------------------------

        app.get("/api/shows/{showId}", ctx -> {
            Show show = catalogue.show(ctx.pathParam("showId")).orElse(null);
            if (show == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Unknown show"));
                return;
            }
            List<Seat> seats = Seats.layout(show.rows(), show.cols());
            List<String> booked = bookings.bookedSeats(show);
            ctx.json(new Dtos.ShowDetail(
                    show,
                    catalogue.movie(show.movieId()).orElse(null),
                    catalogue.theatre(show.theatreId()).orElse(null),
                    show.rows(),
                    show.cols(),
                    seats,
                    booked));
        });

        // --- Hold seats (phase 1) ----------------------------------------------
        // Reserves seats during "payment". A hold blocks other users but is NOT
        // broadcast over SSE — the public seat map does not change on a hold.

        app.post("/api/shows/{showId}/hold", ctx -> {
            Show show = requireShow(catalogue, ctx);
            if (show == null) {
                return;
            }
            Dtos.HoldRequest req = ctx.bodyAsClass(Dtos.HoldRequest.class);
            Object result = bookings.hold(show, req.seatIds(), req.holderId());

            switch (result) {
                case ShowProtocol.Held held -> {
                    int ttl = (int) Math.max(0,
                            (held.expiresAtEpochMs() - System.currentTimeMillis()) / 1000);
                    ctx.json(Dtos.HoldResponse.held(
                            held.holdId(), held.seatIds(), held.expiresAtEpochMs(), ttl,
                            totalPrice(show, held.seatIds())));
                }
                case ShowProtocol.Rejected no -> ctx.status(HttpStatus.CONFLICT)
                        .json(Dtos.HoldResponse.rejected(no.reason(), no.conflictingSeats()));
                default -> ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "Unexpected hold result"));
            }
        });

        // --- Confirm booking (phase 2) -----------------------------------------
        // Turns a hold into a booking. THIS is the only step that changes the
        // public seat map, so it is the only step that broadcasts over SSE.

        app.post("/api/shows/{showId}/confirm", ctx -> {
            Show show = requireShow(catalogue, ctx);
            if (show == null) {
                return;
            }
            Dtos.ConfirmRequest req = ctx.bodyAsClass(Dtos.ConfirmRequest.class);
            String customer = req.customerName() == null || req.customerName().isBlank()
                    ? "Guest" : req.customerName().trim();

            Object result = bookings.confirm(show, req.holdId(), req.holderId(), customer);

            switch (result) {
                case ShowProtocol.Confirmed ok -> {
                    // Booking done — push the new seat state to everyone watching.
                    seatStream.broadcast(show.id(), ok.allBookedSeats());
                    ctx.json(Dtos.BookingResponse.confirmed(
                            ok.bookingId(), customer, ok.seatIds(),
                            totalPrice(show, ok.seatIds()), ok.allBookedSeats()));
                }
                case ShowProtocol.Rejected no -> ctx.status(HttpStatus.CONFLICT)
                        .json(Dtos.BookingResponse.rejected(no.reason(), no.conflictingSeats()));
                default -> ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .json(Map.of("error", "Unexpected confirm result"));
            }
        });

        // --- Release a hold (user cancelled) -----------------------------------

        app.post("/api/shows/{showId}/release", ctx -> {
            Show show = requireShow(catalogue, ctx);
            if (show == null) {
                return;
            }
            Dtos.ReleaseRequest req = ctx.bodyAsClass(Dtos.ReleaseRequest.class);
            boolean released = bookings.release(show, req.holdId(), req.holderId());
            ctx.json(Map.of("released", released));
        });

        // --- Realtime seat updates (SSE) ---------------------------------------

        // NOTE: browsers' EventSource sends "Accept: text/event-stream", which
        // Javalin's SseHandler requires to upgrade the connection.
        app.get("/api/shows/{showId}/stream", new SseHandler(client -> {
            String showId = client.ctx().pathParam("showId");
            Show show = catalogue.show(showId).orElse(null);
            if (show == null) {
                client.close();
                return;
            }
            seatStream.register(showId, client);
            // Send the current state immediately so a late joiner is in sync.
            seatStream.sendSnapshot(client, bookings.bookedSeats(show));
        }));

        BatchedMessageJournal<ShowProtocol.Command> journalRef = journal;
        SnapshotStore<ShowState> snapshotsRef = snapshots;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Cinetor...");
            app.stop();
            system.shutdown();
            if (snapshotsRef != null) {
                snapshotsRef.close();
            }
            // The batched journal flushes on the system shutdown path; nothing else to close.
        }));

        // In the stateful modes, spawn + replay every show's actor up front so the
        // first real request per show doesn't pay the cold-start init cost (and
        // possibly time out). No-op in memory mode.
        //
        // This runs BEFORE app.start so the HTTP port only opens once the actors
        // are warm: otherwise the health endpoint would report ready while cold
        // actors are still initialising, and a readiness probe could route traffic
        // that then times out with a 500. The ask path doesn't need the web server.
        BookingService.WarmUpResult warm = bookings.warmUp(catalogue.allShows());
        if (warm.total() > 0) {
            if (warm.complete()) {
                log.info("Warmed {} seat actors in {} ms", warm.total(), warm.elapsedMs());
            } else {
                // Fail-open: we still start (serving the actors that are ready), but
                // make the shortfall visible instead of swallowing it. A lingering
                // gap here points at an actor that cannot recover (e.g. a corrupt
                // journal), which would 500 on its show until addressed.
                log.warn("Warm-up incomplete: {}/{} seat actors ready after {} ms; "
                                + "the remainder will warm on first access (or are failing to recover)",
                        warm.warmed(), warm.total(), warm.elapsedMs());
            }
        }

        app.start(port);
        log.info("Cinetor backend listening on http://localhost:{} (actor mode: {})", port, mode.label());
    }

    /**
     * Registers gauges that expose what the seat actors' mailboxes are doing under
     * backpressure. Only meaningful in {@code stateful-backpressure} mode — the
     * system-level backpressure monitor is absent otherwise, so the gauges simply
     * report 0 and never throw.
     */
    private static void registerBackpressureMetrics(Metrics metrics, ActorSystem system, BookingService bookings) {
        metrics.gauge("cinetor.backpressure.active.actors",
                "Seat actors currently in a backpressured state", bookings,
                b -> b.activeActorPids().stream()
                        .filter(pid -> safeBackpressureActive(system, pid))
                        .count());
        metrics.gauge("cinetor.backpressure.max.fill.ratio",
                "Highest mailbox fill ratio across all seat actors (0..1)", bookings,
                b -> b.activeActorPids().stream()
                        .mapToDouble(pid -> safeFillRatio(system, pid))
                        .max().orElse(0.0));
        metrics.gauge("cinetor.backpressure.max.mailbox.size",
                "Largest current mailbox size across all seat actors", bookings,
                b -> b.activeActorPids().stream()
                        .mapToDouble(pid -> safeMailboxSize(system, pid))
                        .max().orElse(0.0));
        metrics.gauge("cinetor.backpressure.dropped.total",
                "Total messages dropped by backpressure across all seat actors", bookings,
                b -> b.activeActorPids().stream()
                        .mapToDouble(pid -> safeDropped(system, pid))
                        .sum());
    }

    private static boolean safeBackpressureActive(ActorSystem system, com.cajunsystems.Pid pid) {
        try {
            return system.isBackpressureActive(pid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static double safeFillRatio(ActorSystem system, com.cajunsystems.Pid pid) {
        try {
            return system.getBackpressureStatus(pid).getFillRatio();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static double safeMailboxSize(ActorSystem system, com.cajunsystems.Pid pid) {
        try {
            return system.getBackpressureStatus(pid).getCurrentSize();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    private static double safeDropped(ActorSystem system, com.cajunsystems.Pid pid) {
        try {
            return system.getBackpressureStatus(pid).getDroppedMessageCount();
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /** Resolves the show from the path, writing a 404 and returning null if unknown. */
    private static Show requireShow(CatalogueData catalogue, io.javalin.http.Context ctx) {
        Show show = catalogue.show(ctx.pathParam("showId")).orElse(null);
        if (show == null) {
            ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Unknown show"));
        }
        return show;
    }

    private static int totalPrice(Show show, List<String> seatIds) {
        Map<String, Seat> byId = Seats.layout(show.rows(), show.cols()).stream()
                .collect(java.util.stream.Collectors.toMap(Seat::id, s -> s));
        return seatIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .mapToInt(Seat::price).sum();
    }

    private static int resolvePort() {
        String prop = System.getProperty("cinetor.port");
        if (prop == null) {
            prop = System.getenv("PORT");
        }
        try {
            return prop == null ? 7070 : Integer.parseInt(prop);
        } catch (NumberFormatException e) {
            return 7070;
        }
    }

    /**
     * Actor mode. Defaults to {@code memory} (the demo's original behaviour);
     * override with {@code -Dcinetor.actorMode=stateful} (or the {@code ACTOR_MODE}
     * env var). Accepts {@code memory | stateful | stateful-backpressure}.
     */
    private static ActorMode resolveActorMode() {
        String prop = System.getProperty("cinetor.actorMode");
        if (prop == null) {
            prop = System.getenv("ACTOR_MODE");
        }
        return ActorMode.fromConfig(prop);
    }

    /** Hold window in ms. Defaults to 5 minutes; override with -Dcinetor.holdSeconds=N. */
    private static long resolveHoldMillis() {
        String prop = System.getProperty("cinetor.holdSeconds");
        if (prop == null) {
            prop = System.getenv("HOLD_SECONDS");
        }
        try {
            long seconds = prop == null ? 300 : Long.parseLong(prop);
            return Math.max(1, seconds) * 1000L;
        } catch (NumberFormatException e) {
            return 300_000L;
        }
    }
}
