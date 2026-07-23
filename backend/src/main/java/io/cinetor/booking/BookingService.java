package io.cinetor.booking;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import io.cinetor.actor.ShowActor;
import io.cinetor.actor.ShowProtocol;
import io.cinetor.catalog.CatalogueData;
import io.cinetor.model.Catalogue.Show;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the HTTP layer and the Cajun actor system.
 *
 * <p>Each show gets exactly one {@link ShowActor}, created lazily on first
 * access. Reads and bookings are performed via the ask pattern, so the HTTP
 * thread hands the request to the actor and waits for the actor's reply — all
 * mutation of a show's seats is serialised inside that one actor.
 */
public class BookingService {

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private final ActorSystem system;
    private final CatalogueData catalogue;
    private final Map<String, Pid> actors = new ConcurrentHashMap<>();

    public BookingService(ActorSystem system, CatalogueData catalogue) {
        this.system = system;
        this.catalogue = catalogue;
    }

    private Pid actorFor(Show show) {
        return actors.computeIfAbsent(show.id(), id ->
                system.actorOf(new ShowActor(show.rows(), show.cols()))
                        .withId("show-" + id)
                        .spawn());
    }

    /** Returns the list of currently booked seat ids for a show. */
    public List<String> bookedSeats(Show show) {
        CompletableFuture<ShowProtocol.Snapshot> future =
                system.ask(actorFor(show), new ShowProtocol.GetSnapshot(), ASK_TIMEOUT);
        return await(future).bookedSeats();
    }

    /** Attempts to book seats for a show, returning the actor's outcome. */
    public ShowProtocol.BookResult book(Show show, List<String> seatIds, String customerName) {
        CompletableFuture<ShowProtocol.BookResult> future =
                system.ask(actorFor(show), new ShowProtocol.Book(seatIds, customerName), ASK_TIMEOUT);
        return await(future);
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(ASK_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Actor request failed: " + e.getMessage(), e);
        }
    }
}
