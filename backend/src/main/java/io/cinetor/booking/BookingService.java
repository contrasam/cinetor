package io.cinetor.booking;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import io.cinetor.actor.ShowActor;
import io.cinetor.actor.ShowProtocol;
import io.cinetor.catalog.CatalogueData;
import io.cinetor.metrics.Metrics;
import io.cinetor.model.Catalogue.Show;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
    private final Metrics metrics;
    private final Map<String, Pid> actors = new ConcurrentHashMap<>();

    public BookingService(ActorSystem system, CatalogueData catalogue, long holdMillis, Metrics metrics) {
        this.system = system;
        this.catalogue = catalogue;
        this.holdMillis = holdMillis;
        this.metrics = metrics;
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
        return timedAsk("snapshot", () -> {
            CompletableFuture<ShowProtocol.Snapshot> future =
                    system.ask(actorFor(show), new ShowProtocol.GetSnapshot(), ASK_TIMEOUT);
            return await(future).bookedSeats();
        });
    }

    /** Holds seats for the payment window. Reply is a {@code Held} or {@code Rejected}. */
    public Object hold(Show show, List<String> seatIds, String holderId) {
        Object result = timedAsk("hold", () -> {
            CompletableFuture<Object> future =
                    system.ask(actorFor(show), new ShowProtocol.Hold(seatIds, holderId), ASK_TIMEOUT);
            return await(future);
        });
        metrics.outcome("hold", result instanceof ShowProtocol.Held ? "held" : "rejected").increment();
        return result;
    }

    /** Confirms a hold into a booking. Reply is a {@code Confirmed} or {@code Rejected}. */
    public Object confirm(Show show, String holdId, String holderId, String customerName) {
        Object result = timedAsk("confirm", () -> {
            CompletableFuture<Object> future = system.ask(
                    actorFor(show), new ShowProtocol.Confirm(holdId, holderId, customerName), ASK_TIMEOUT);
            return await(future);
        });
        metrics.outcome("confirm", result instanceof ShowProtocol.Confirmed ? "confirmed" : "rejected").increment();
        return result;
    }

    /** Releases a hold (e.g. the user cancelled). */
    public boolean release(Show show, String holdId, String holderId) {
        boolean released = timedAsk("release", () -> {
            CompletableFuture<ShowProtocol.Released> future =
                    system.ask(actorFor(show), new ShowProtocol.Release(holdId, holderId), ASK_TIMEOUT);
            return await(future).released();
        });
        metrics.outcome("release", released ? "released" : "noop").increment();
        return released;
    }

    /** Times one actor ask (mailbox wait + processing) and counts failures. */
    private <T> T timedAsk(String op, Supplier<T> call) {
        Timer.Sample sample = Timer.start(metrics.registry());
        try {
            return call.get();
        } catch (RuntimeException e) {
            metrics.askErrors(op).increment();
            throw e;
        } finally {
            sample.stop(metrics.askTimer(op));
        }
    }

    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(ASK_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Actor request failed: " + e.getMessage(), e);
        }
    }
}
