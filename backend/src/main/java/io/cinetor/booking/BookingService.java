package io.cinetor.booking;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.builder.StatefulActorBuilder;
import com.cajunsystems.config.BackpressureConfig;
import com.cajunsystems.config.ResizableMailboxConfig;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import io.cinetor.actor.ActorMode;
import io.cinetor.actor.ShowActor;
import io.cinetor.actor.ShowProtocol;
import io.cinetor.actor.ShowState;
import io.cinetor.actor.StatefulShowActor;
import io.cinetor.catalog.CatalogueData;
import io.cinetor.metrics.Metrics;
import io.cinetor.model.Catalogue.Show;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bridges the HTTP layer and the Cajun actor system.
 *
 * <p>Each show gets exactly one seat actor, created lazily on first access.
 * Reads, holds and bookings are performed via the ask pattern, so the HTTP
 * thread hands the request to the actor and waits for the actor's reply — all
 * mutation of a show's seats is serialised inside that one actor.
 *
 * <p>The actor <em>flavour</em> depends on the configured {@link ActorMode}:
 * an in-memory {@link ShowActor}, or a persistent {@link StatefulShowActor}
 * (optionally with mailbox backpressure). The booking API is identical across
 * modes; only how the actor is spawned differs, which is what lets the
 * comparison suite swap modes without touching the web layer.
 */
public class BookingService {

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);
    // Per-ask timeout for the one-time startup warm-up: long enough for a cold
    // persistent actor's first reply (state init), short enough that a straggler
    // resolves fast instead of stalling the whole warm-up pass.
    private static final Duration WARMUP_TIMEOUT = Duration.ofSeconds(6);

    private final ActorSystem system;
    private final CatalogueData catalogue;
    private final long holdMillis;
    private final Metrics metrics;
    private final ActorMode mode;
    // Shared persistence stores for the stateful modes (each actor's journal and
    // snapshots are keyed internally by its actor id, so one instance serves all
    // shows). Null in MEMORY mode.
    private final BatchedMessageJournal<ShowProtocol.Command> journal;
    private final SnapshotStore<ShowState> snapshots;
    private final Map<String, Pid> actors = new ConcurrentHashMap<>();

    public BookingService(ActorSystem system, CatalogueData catalogue, long holdMillis, Metrics metrics) {
        this(system, catalogue, holdMillis, metrics, ActorMode.MEMORY, null, null);
    }

    public BookingService(ActorSystem system, CatalogueData catalogue, long holdMillis, Metrics metrics,
                          ActorMode mode,
                          BatchedMessageJournal<ShowProtocol.Command> journal,
                          SnapshotStore<ShowState> snapshots) {
        this.system = system;
        this.catalogue = catalogue;
        this.holdMillis = holdMillis;
        this.metrics = metrics;
        this.mode = mode;
        this.journal = journal;
        this.snapshots = snapshots;
    }

    public long holdMillis() {
        return holdMillis;
    }

    public ActorMode mode() {
        return mode;
    }

    /** Pids of all seat actors spawned so far — used by the backpressure metrics sampler. */
    public Collection<Pid> activeActorPids() {
        return actors.values();
    }

    /** Outcome of {@link #warmUp}: how many of {@code total} actors confirmed ready. */
    public record WarmUpResult(int total, int warmed, long elapsedMs) {
        /** True if every actor replied during warm-up. */
        public boolean complete() {
            return warmed >= total;
        }
    }

    /**
     * Eagerly spawn and initialise every show's actor, in parallel, before the
     * app serves traffic. Only matters for the stateful modes: a persistent actor
     * pays a one-time state-initialisation cost (snapshot lookup + journal replay)
     * on its first message — a few seconds cold — and doing that lazily means the
     * first real request for each show can exceed the ask timeout. Warming them
     * concurrently overlaps that cost into one short startup pause so users never
     * see a cold-start 500. A no-op in {@code memory} mode (nothing to replay).
     *
     * <p>Warm-up is deliberately <em>fail-open</em>: a per-actor ask that doesn't
     * reply in time is almost always a slow cold-start (the actor warms moments
     * later and serves the real request fine), so refusing to start over it would
     * mean false unreadiness, and hard-failing on one show's unrecoverable journal
     * would needlessly take down every other show. Instead the result reports how
     * many actors actually confirmed, so the caller can log a warning when some
     * did not (e.g. a genuinely un-recoverable actor) rather than failing silently.
     */
    public WarmUpResult warmUp(Collection<Show> shows) {
        if (!mode.isStateful() || shows.isEmpty()) {
            return new WarmUpResult(0, 0, 0);
        }
        long t0 = System.currentTimeMillis();
        // Two concurrent passes. A persistent actor's first message kicks off its
        // state-init (snapshot lookup + journal replay), which for a cold actor
        // finishes a little after that first ask returns; a second pass a moment
        // later confirms every actor is hot. Firing all shows at once (rather than
        // in batches) lets the inits overlap, and a short per-ask timeout means a
        // straggler resolves quickly instead of stalling the whole pass.
        int warmed = 0;
        for (int pass = 0; pass < 2; pass++) {
            List<CompletableFuture<?>> inflight = new ArrayList<>();
            for (Show show : shows) {
                inflight.add(system.ask(actorFor(show), new ShowProtocol.GetSnapshot(), WARMUP_TIMEOUT)
                        .exceptionally(ex -> null));
            }
            try {
                CompletableFuture.allOf(inflight.toArray(CompletableFuture[]::new))
                        .get(WARMUP_TIMEOUT.toMillis() + 1_000, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Best-effort: stragglers warm on first real access.
            }
            // On the final pass, count actors that actually replied (failures were
            // mapped to null above), so the caller can surface any that didn't.
            if (pass == 1) {
                warmed = (int) inflight.stream().filter(f -> f.getNow(null) != null).count();
            }
        }
        return new WarmUpResult(shows.size(), warmed, System.currentTimeMillis() - t0);
    }

    private Pid actorFor(Show show) {
        return actors.computeIfAbsent(show.id(), id -> switch (mode) {
            case MEMORY -> system.actorOf(new ShowActor(show.rows(), show.cols(), holdMillis))
                    .withId("show-" + id)
                    .spawn();
            case STATEFUL, STATEFUL_BACKPRESSURE -> spawnStateful(id, show);
        });
    }

    @SuppressWarnings("removal") // ResizableMailboxConfig is the only mailbox config the builder accepts in 0.7.0
    private Pid spawnStateful(String id, Show show) {
        StatefulActorBuilder<ShowState, ShowProtocol.Command> builder =
                system.statefulActorOf(
                                new StatefulShowActor(show.rows(), show.cols(), holdMillis),
                                ShowState.empty())
                        .withId("show-" + id)
                        .withPersistence(journal, snapshots);
        if (mode.usesBackpressure()) {
            builder = builder
                    .withBackpressureConfig(backpressureConfig())
                    .withMailboxConfig(mailboxConfig());
        }
        return builder.spawn();
    }

    /**
     * Backpressure thresholds for the seat actors. BLOCK is deliberate: a booking
     * must never be silently dropped, so under overload the sender slows down
     * (surfacing as latency, then the 5s ask-timeout) rather than losing a hold or
     * confirm the way DROP_NEW / DROP_OLDEST would.
     */
    public static BackpressureConfig backpressureConfig() {
        return new BackpressureConfig.Builder()
                .strategy(com.cajunsystems.backpressure.BackpressureStrategy.BLOCK)
                .warningThreshold(0.7f)
                .criticalThreshold(0.85f)
                .recoveryThreshold(0.3f)
                .build();
    }

    /**
     * A bounded, resizable mailbox so backpressure has an actual ceiling to push
     * against. The cap is deliberately modest: a single seat actor that already
     * has 256 booking commands queued is badly overloaded, and capping there lets
     * the backpressure monitor actually engage under a saturation test rather than
     * hiding behind an effectively unbounded queue. Raise {@code maxCapacity} for
     * a deployment that would rather buffer than push back.
     */
    @SuppressWarnings("removal")
    static ResizableMailboxConfig mailboxConfig() {
        return new ResizableMailboxConfig()
                .setInitialCapacity(64)
                .setMaxCapacity(256);
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
        // The hold id and request time are minted here, not in the actor, so that
        // replaying this command during stateful recovery rebuilds the identical
        // hold (see ShowProtocol.Hold).
        String holdId = "HOLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long now = System.currentTimeMillis();
        Object result = timedAsk("hold", () -> {
            CompletableFuture<Object> future = system.ask(
                    actorFor(show), new ShowProtocol.Hold(seatIds, holderId, holdId, now), ASK_TIMEOUT);
            return await(future);
        });
        metrics.outcome("hold", result instanceof ShowProtocol.Held ? "held" : "rejected").increment();
        return result;
    }

    /** Confirms a hold into a booking. Reply is a {@code Confirmed} or {@code Rejected}. */
    public Object confirm(Show show, String holdId, String holderId, String customerName) {
        long now = System.currentTimeMillis();
        Object result = timedAsk("confirm", () -> {
            CompletableFuture<Object> future = system.ask(
                    actorFor(show), new ShowProtocol.Confirm(holdId, holderId, customerName, now), ASK_TIMEOUT);
            return await(future);
        });
        metrics.outcome("confirm", result instanceof ShowProtocol.Confirmed ? "confirmed" : "rejected").increment();
        return result;
    }

    /** Releases a hold (e.g. the user cancelled). */
    public boolean release(Show show, String holdId, String holderId) {
        long now = System.currentTimeMillis();
        boolean released = timedAsk("release", () -> {
            CompletableFuture<ShowProtocol.Released> future = system.ask(
                    actorFor(show), new ShowProtocol.Release(holdId, holderId, now), ASK_TIMEOUT);
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
