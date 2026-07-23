package io.cinetor;

import com.cajunsystems.ActorSystem;
import io.cinetor.actor.ShowProtocol;
import io.cinetor.booking.BookingService;
import io.cinetor.catalog.CatalogueData;
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

        ActorSystem system = new ActorSystem();
        CatalogueData catalogue = new CatalogueData();
        BookingService bookings = new BookingService(system, catalogue);
        SeatStreamHub seatStream = new SeatStreamHub();

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
            config.showJavalinBanner = false;
        });

        // --- Catalogue browsing -------------------------------------------------

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

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

        // --- Booking ------------------------------------------------------------

        app.post("/api/shows/{showId}/book", ctx -> {
            Show show = catalogue.show(ctx.pathParam("showId")).orElse(null);
            if (show == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "Unknown show"));
                return;
            }
            Dtos.BookingRequest req = ctx.bodyAsClass(Dtos.BookingRequest.class);
            String customer = req.customerName() == null || req.customerName().isBlank()
                    ? "Guest" : req.customerName().trim();

            ShowProtocol.BookResult result = bookings.book(show, req.seatIds(), customer);

            switch (result) {
                case ShowProtocol.Confirmed ok -> {
                    // Push the new seat state to everyone watching this show.
                    seatStream.broadcast(show.id(), ok.allBookedSeats());
                    int total = totalPrice(show, ok.seatIds());
                    ctx.json(Dtos.BookingResponse.confirmed(
                            ok.bookingId(), customer, ok.seatIds(), total, ok.allBookedSeats()));
                }
                case ShowProtocol.Rejected no -> ctx.status(HttpStatus.CONFLICT)
                        .json(Dtos.BookingResponse.rejected(no.reason(), no.conflictingSeats()));
            }
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Cinetor...");
            app.stop();
            system.shutdown();
        }));

        app.start(port);
        log.info("Cinetor backend listening on http://localhost:{}", port);
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
}
