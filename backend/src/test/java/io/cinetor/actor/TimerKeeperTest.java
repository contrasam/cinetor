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

/**
 * Unit tests for {@link TimerKeeper}: it schedules one timed message per new
 * deadline, doesn't re-schedule ones it has already armed, forgets deadlines that
 * disappear, and — the point of the whole thing — re-arms everything when its
 * cache starts cold, which is what a restart looks like.
 */
class TimerKeeperTest {

    @Test
    void armsEachDeadlineOnceAndForgetsRemovedOnes() {
        TimerKeeper<String> keeper = new TimerKeeper<>();
        RecordingContext ctx = new RecordingContext();
        long future = System.currentTimeMillis() + 60_000;

        keeper.reconcile(Map.of("a", future, "b", future), ctx, id -> "expire-" + id);
        assertEquals(2, ctx.scheduled.size(), "both new deadlines are armed");

        // Same deadlines again → nothing new scheduled.
        keeper.reconcile(Map.of("a", future, "b", future), ctx, id -> "expire-" + id);
        assertEquals(2, ctx.scheduled.size(), "already-armed deadlines are not re-scheduled");

        // 'a' disappears, 'c' appears → only 'c' is newly armed.
        keeper.reconcile(Map.of("b", future, "c", future), ctx, id -> "expire-" + id);
        assertEquals(3, ctx.scheduled.size(), "only the new deadline 'c' is armed");
        assertEquals("expire-c", ctx.scheduled.get(2).message());

        // 'a' returns → armed again, because it was forgotten when it left.
        keeper.reconcile(Map.of("a", future, "b", future, "c", future), ctx, id -> "expire-" + id);
        assertEquals(4, ctx.scheduled.size(), "a re-added deadline is armed afresh");
        assertEquals("expire-a", ctx.scheduled.get(3).message());
    }

    @Test
    void coldKeeperReArmsEverything() {
        // Simulates a restart: a brand-new keeper (empty cache) handed the deadlines
        // that were restored into state must arm all of them.
        TimerKeeper<String> restarted = new TimerKeeper<>();
        RecordingContext ctx = new RecordingContext();
        long past = System.currentTimeMillis() - 10_000; // already lapsed

        restarted.reconcile(Map.of("x", past, "y", past), ctx, id -> "expire-" + id);

        assertEquals(2, ctx.scheduled.size(), "a cold keeper arms every restored deadline");
        // Lapsed deadlines schedule immediately.
        ctx.scheduled.forEach(s -> assertEquals(0L, s.delayMs(), "a lapsed deadline fires immediately"));
    }

    private record Scheduled(Object message, long delayMs) {
    }

    /** Captures scheduled self-messages; everything else is unused here. */
    private static final class RecordingContext implements ActorContext {
        final java.util.List<Scheduled> scheduled = new java.util.ArrayList<>();

        @Override public <T> void tellSelf(T message, long delay, TimeUnit unit) {
            scheduled.add(new Scheduled(message, unit.toMillis(delay)));
        }
        @Override public <T> void tellSelf(T message) {
        }
        @Override public Optional<Pid> getSender() {
            return Optional.empty();
        }
        @Override public <T> void tell(Pid target, T message) {
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
