package io.cinetor.actor;

/**
 * A seat actor into which a deliberate crash can be injected, so the supervision
 * and crash-recovery story can be exercised on demand.
 *
 * <p>Implemented by both {@link ShowActor} and {@link StatefulShowActor}. Arming
 * is intentionally an <em>out-of-band</em> signal — a direct method call on the
 * handler object, not a message on the mailbox — for one crucial reason: every
 * message a stateful actor receives is written to its journal <em>before</em> it
 * is processed, so a "please panic" <em>command</em> would be replayed on
 * recovery and crash the actor again, an un-recoverable poison pill. A transient
 * latch on the handler instance never enters the journal, so a supervised restart
 * replays only the real booking commands and rebuilds the seat state cleanly.
 */
public interface FaultInjectable {

    /**
     * Arm a one-shot panic: the <em>next</em> hold this actor processes throws
     * mid-flight, after the hold command has been journaled but before it is
     * applied in memory. The latch clears itself as it fires, so the restart that
     * follows (driven by the supervising {@link TheatreActor}) replays the
     * journaled hold without re-triggering the fault.
     */
    void armPanic();
}
