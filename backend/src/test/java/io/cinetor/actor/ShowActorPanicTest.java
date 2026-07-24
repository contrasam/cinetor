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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The in-memory half of the crash story: the same injected fault
 * ({@link FaultInjectable}) that a persistent show survives is genuinely lossy for
 * a plain {@link ShowActor}. There is no journal, so a hold interrupted by the
 * panic simply vanishes — nothing to replay, nothing to recover. This is the
 * contrast that {@link SupervisedRecoveryTest} plays against, and the reason the
 * crash-recovery guarantee is a property of the {@code stateful} modes, not the
 * default {@code memory} one.
 */
class ShowActorPanicTest {

    private static final long HOLD_MS = 300_000L;
    private static final long T = 1_000_000L;

    @Test
    void panicMidHoldLosesTheReservationAcrossRestart() {
        ShowActor actor = new ShowActor(5, 5, HOLD_MS);
        StubContext ctx = new StubContext();

        // A genuine reservation on A1.
        Object held = replyOf(actor,
                new ShowProtocol.Hold(List.of("A1"), "ada", "HOLD-1", T), ctx);
        assertInstanceOf(ShowProtocol.Held.class, held);

        // Arm the fault; the next hold panics instead of reserving the seats.
        actor.armPanic();
        assertThrows(IllegalStateException.class, () ->
                actor.receive(new ShowProtocol.Hold(List.of("B2"), "bob", "HOLD-2", T + 1_000), ctx));

        // Simulate the supervised restart: preStart runs (clearing the fault) and,
        // because there is no journal, the in-memory actor comes back with a blank
        // seat map. The A1 reservation is gone — a new instance holds nothing.
        ShowActor restarted = new ShowActor(5, 5, HOLD_MS);
        restarted.preStart(ctx);
        Object afterRestart = replyOf(restarted,
                new ShowProtocol.Hold(List.of("A1"), "intruder", "HOLD-3", T + 2_000), ctx);
        assertInstanceOf(ShowProtocol.Held.class, afterRestart);
    }

    @Test
    void preStartClearsTheFault() {
        ShowActor actor = new ShowActor(5, 5, HOLD_MS);
        StubContext ctx = new StubContext();

        actor.armPanic();
        // preStart (the restart hook) disarms the fault, so the next hold is normal.
        actor.preStart(ctx);
        Object next = replyOf(actor,
                new ShowProtocol.Hold(List.of("B2"), "bob", "HOLD-1", T), ctx);
        assertInstanceOf(ShowProtocol.Held.class, next);
    }

    private Object replyOf(ShowActor actor, ShowProtocol.Command command, StubContext ctx) {
        ctx.lastReply = null;
        actor.receive(command, ctx);
        return ctx.lastReply;
    }

    /** Minimal context that captures the actor's reply and no-ops scheduling. */
    private static final class StubContext implements ActorContext {
        Object lastReply;

        @Override public Optional<Pid> getSender() {
            return Optional.of(new Pid("sender", null));
        }
        @Override public <T> void tell(Pid target, T message) {
            lastReply = message;
        }
        @Override public <T> void tellSelf(T message, long delay, TimeUnit unit) {
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
