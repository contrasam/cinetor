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
 * access. Reads, holds and bookings are performed via the ask pattern, so the
 * HTTP thread hands the request to the actor and waits for the actor's reply —
 * all mutation of a show's seats is serialised inside that one actor.
 */
public class BookingService {

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private final ActorSystem system;
    private final CatalogueData catalogue;
    private final long holdMillis;
    private final Map<String, Pid> actors = new ConcurrentHashMap<>();

    public BookingService(ActorSystem system, CatalogueData catalogue, long holdMillis) {
        this.system = system;
        this.catalogue = catalogue;
        this.holdMillis = holdMillis;
    }

    public long holdMillis() {
        return holdMillis;
    }

    private Pid actorFor(Show show) {
        return actors.computeIfAbsent(show.id(), id ->
                system.actorOf(new ShowActor(show.rows(), show.cols(), holdMillis))
                        .withId("show-" + id)
                        .spawn());
    }

    /** Returns the list of currently booked seat ids for a show (holds excluded). */
    public List<String> bookedSeats(Show show) {
        CompletableFuture<ShowProtocol.Snapshot> future =
                system.ask(actorFor(show), new ShowProtocol.GetSnapshot(), ASK_TIMEOUT);
        return await(future).bookedSeats();
    }

    /** Holds seats for the payment window. Reply is a {@code Held} or {@code Rejected}. */
    public Object hold(Show show, List<String> seatIds, String holderId) {
        CompletableFuture<Object> future =
                system.ask(actorFor(show), new ShowProtocol.Hold(seatIds, holderId), ASK_TIMEOUT);
        return await(future);
    }

    /** Confirms a hold into a booking. Reply is a {@code Confirmed} or {@code Rejected}. */
    public Object confirm(Show show, String holdId, String holderId, String customerName) {
        CompletableFuture<Object> future = system.ask(
                actorFor(show), new ShowProtocol.Confirm(holdId, holderId, customerName), ASK_TIMEOUT);
        return await(future);
    }

    /** Releases a hold (e.g. the user cancelled). */
    public boolean release(Show show, String holdId, String holderId) {
        CompletableFuture<ShowProtocol.Released> future =
                system.ask(actorFor(show), new ShowProtocol.Release(holdId, holderId), ASK_TIMEOUT);
        return await(future).released();
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(ASK_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Actor request failed: " + e.getMessage(), e);
        }
    }
}
