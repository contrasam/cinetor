package io.cinetor.actor;

import com.cajunsystems.ActorContext;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.ReplyingMessage;
import com.cajunsystems.builder.ActorBuilder;
import com.cajunsystems.handler.Handler;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Determinism tests for {@link StatefulShowActor}.
 *
 * <p>Cajun recovers a persistent actor by <em>replaying</em> its journaled
 * commands back through the handler. That only reconstructs the right state if
 * the handler is deterministic — the same command stream must always produce the
 * same {@link ShowState}. These tests feed the identical stream twice (a stand-in
 * for "live" then "replay") through a stub context and assert the states match,
 * and that a hold confirmed within its window is honoured even when the wall
 * clock is far past the hold's timestamps.
 *
 * <p>This is the property the earlier design violated: minting the hold id with
 * {@code UUID.randomUUID()} and judging expiry with {@code System.currentTimeMillis()}
 * inside the handler made replay produce a <em>different</em> id and expiry than it
 * did live, so a replayed {@code Confirm} referencing the original id silently
 * dropped the booking. The id and timestamps now ride on the commands.
 */
class StatefulShowActorRecoveryTest {

    private static final long HOLD_MS = 300_000L;
    // Deliberately ancient timestamps: if the handler judged expiry by the wall
    // clock, these holds would look long-expired and the confirm would be dropped.
    private static final long T = 1_000_000L;

    /** Replays a command stream from empty state and returns the final state. */
    private ShowState run(List<ShowProtocol.Command> commands) {
        return run(commands, new StubContext());
    }

    private ShowState run(List<ShowProtocol.Command> commands, StubContext ctx) {
        StatefulShowActor actor = new StatefulShowActor(5, 5, HOLD_MS);
        ShowState state = ShowState.empty();
        for (ShowProtocol.Command cmd : commands) {
            state = actor.receive(cmd, state, ctx);
        }
        return state;
    }

    @Test
    void sameCommandStreamProducesSameState() {
        List<ShowProtocol.Command> stream = List.of(
                new ShowProtocol.Hold(List.of("A1", "A2"), "holder-1", "HOLD-1", T),
                new ShowProtocol.Confirm("HOLD-1", "holder-1", "Ada", T + 1_000));

        ShowState live = run(stream);
        ShowState replay = run(stream); // a fresh actor + fresh state, same commands

        assertEquals(List.of("A1", "A2"), List.copyOf(live.booked()));
        assertEquals(live.booked(), replay.booked(),
                "replaying the same commands must rebuild the same booked set");
    }

    @Test
    void confirmWithinWindowIsHonouredRegardlessOfWallClock() {
        // The hold's expiry (T + HOLD_MS) is decades in the past relative to the
        // real clock; a wall-clock expiry check would purge it before the confirm.
        ShowState state = run(List.of(
                new ShowProtocol.Hold(List.of("B2"), "holder-1", "HOLD-2", T),
                new ShowProtocol.Confirm("HOLD-2", "holder-1", "Ada", T + 5_000)));

        assertEquals(List.of("B2"), List.copyOf(state.booked()),
                "a hold confirmed within its window must book, judged by the command clock");
    }

    @Test
    void confirmAfterExpiryWindowIsRejected() {
        // Confirm arrives after the hold window (by command time), so the lazy
        // purge should have dropped the hold and the seat stays unbooked.
        ShowState state = run(List.of(
                new ShowProtocol.Hold(List.of("C3"), "holder-1", "HOLD-3", T),
                new ShowProtocol.Confirm("HOLD-3", "holder-1", "Ada", T + HOLD_MS + 1)));

        assertTrue(state.booked().isEmpty(),
                "a hold confirmed after its window elapsed must not book");
    }

    @Test
    void unconfirmedHoldIsNotBooked() {
        ShowState state = run(List.of(
                new ShowProtocol.Hold(List.of("D4"), "holder-1", "HOLD-4", T)));

        assertTrue(state.booked().isEmpty(), "a hold alone is not a booking");
        assertInstanceOf(SeatHold.class, state.holdsById().get("HOLD-4"));
    }

    @Test
    void replayedLapsedHoldSchedulesImmediateExpiry() {
        // A hold whose window (T + HOLD_MS) is already long past — the situation on
        // recovery of an unconfirmed hold. The scheduled ExpireHold must fire for
        // the REMAINING time (0), not a fresh full window, so the seats free at
        // once instead of staying blocked for up to another HOLD_MS after restart.
        StubContext ctx = new StubContext();
        run(List.of(new ShowProtocol.Hold(List.of("E5"), "holder-1", "HOLD-5", T)), ctx);

        assertEquals(0L, ctx.lastExpireDelayMs,
                "an already-lapsed hold must schedule expiry immediately on replay");
    }

    @Test
    void snapshotRestoredHoldIsArmedForExpiryOnFirstMessage() {
        // Simulate recovery from a snapshot: a hold is already present in the state
        // this actor instance is handed, but its Hold command was never replayed
        // (it predates the snapshot). The first message must arm its ExpireHold so
        // it doesn't block seats forever on an idle show.
        StatefulShowActor actor = new StatefulShowActor(5, 5, HOLD_MS);
        StubContext ctx = new StubContext();
        ShowState restored = ShowState.empty()
                .withHold(new SeatHold("HOLD-SNAP", "holder-1", T + HOLD_MS, List.of("A1")));

        // First message after restart (the warm-up read).
        actor.receive(new ShowProtocol.GetSnapshot(), restored, ctx);

        assertEquals("HOLD-SNAP", ctx.lastSelfMessageHoldId,
                "the snapshot-restored hold must be scheduled for expiry");
        assertEquals(0L, ctx.lastExpireDelayMs,
                "its window is long past, so it should expire immediately");
    }

    @Test
    void liveHoldSchedulesExpiryAtFullWindow() {
        // A fresh hold created "now" schedules expiry roughly a full window out.
        StubContext ctx = new StubContext();
        long now = System.currentTimeMillis();
        run(List.of(new ShowProtocol.Hold(List.of("A1"), "holder-1", "HOLD-6", now)), ctx);

        assertTrue(ctx.lastExpireDelayMs > HOLD_MS - 5_000 && ctx.lastExpireDelayMs <= HOLD_MS,
                "a live hold should expire about one full window out, was " + ctx.lastExpireDelayMs);
    }

    /**
     * Minimal {@link ActorContext} for driving the handler directly. Mirrors
     * replay: there is no sender, so replies are dropped, and {@code tellSelf}
     * (the expiry scheduling) is a no-op — exactly how recovery re-applies
     * commands without emitting side effects to the outside world.
     */
    private static final class StubContext implements ActorContext {
        // Records the most recent scheduled self-message (the ExpireHold timer):
        // its delay and target hold id, so tests can assert expiry scheduling.
        long lastExpireDelayMs = -1;
        String lastSelfMessageHoldId = null;

        @Override public Optional<Pid> getSender() {
            return Optional.empty();
        }
        @Override public <T> void tell(Pid target, T message) {
            // no sender in replay; nothing to deliver
        }
        @Override public <T> void tellSelf(T message, long delay, TimeUnit unit) {
            lastExpireDelayMs = unit.toMillis(delay);
            if (message instanceof ShowProtocol.ExpireHold expire) {
                lastSelfMessageHoldId = expire.holdId();
            }
        }
        @Override public <T> void tellSelf(T message) {
        }
        @Override public Pid self() {
            return null;
        }
        @Override public String getActorId() {
            return "stub";
        }
        @Override public <T> void reply(ReplyingMessage message, T response) {
        }
        @Override public <Message> ActorBuilder<Message> childBuilder(Class<? extends Handler<Message>> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> Pid createChild(Class<?> handler, String id) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> Pid createChild(Class<?> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public Pid getParent() {
            return null;
        }
        @Override public Map<String, Pid> getChildren() {
            return Map.of();
        }
        @Override public ActorSystem getSystem() {
            return null;
        }
        @Override public void stop() {
        }
        @Override public <T> void forward(Pid target, T message) {
        }
        @Override public Logger getLogger() {
            return org.slf4j.LoggerFactory.getLogger("stub");
        }
    }
}
