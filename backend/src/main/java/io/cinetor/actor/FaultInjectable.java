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
     * Arm a panic: the <em>next</em> hold this actor processes throws mid-flight,
     * after the hold command has been journaled but before it is applied in memory.
     *
     * <p>The latch deliberately does <b>not</b> clear itself when it fires — it
     * stays armed until the actor restarts, at which point {@code preStart} clears
     * it. That lifecycle is essential, not incidental: the stateful actor retries a
     * throwing {@code processMessage} a few times, and a self-clearing latch would
     * simply succeed on the retry, so the fault would be swallowed and no restart
     * would happen. Kept armed, every attempt throws, the retries are exhausted, and
     * the show's {@code RESTART} supervision (configured by its {@link TheatreActor})
     * kicks in. {@code preStart} then clears the latch on the way back up — before
     * journal replay — so recovery rebuilds the seats without re-triggering the
     * fault. A future implementation must preserve this "armed until preStart"
     * behaviour rather than clearing on fire.
     */
    void armPanic();
}
