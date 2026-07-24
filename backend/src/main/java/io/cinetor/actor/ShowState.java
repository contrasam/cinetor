package io.cinetor.actor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable seat state for one show, used by {@link StatefulShowActor}.
 *
 * <p>This is the persistent-actor counterpart of the mutable fields inside
 * {@link ShowActor}. Where {@code ShowActor} mutates {@code booked} /
 * {@code holdsById} / {@code seatToHoldId} in place, a stateful actor is
 * functional: each message returns a <em>new</em> {@code ShowState}, and Cajun
 * snapshots that value (Java serialization) so the show can be rebuilt after a
 * restart. Hence every field — and everything it references — is
 * {@link Serializable}.
 *
 * <p>The three collections mirror {@code ShowActor} exactly:
 * <ul>
 *   <li><b>booked</b> — permanently reserved seats (the only public set).</li>
 *   <li><b>holdsById</b> — live holds keyed by hold id.</li>
 *   <li><b>seatToHoldId</b> — reverse index seat → owning hold, for O(1)
 *       availability checks.</li>
 * </ul>
 *
 * <p>Copy-on-write is deliberate: producing a fresh state per message is part of
 * what the stateful mode costs relative to the in-memory mode, and the
 * comparison suite measures exactly that.
 *
 * <p>{@code clockEpochMs} is a <b>logical clock</b>: the latest request timestamp
 * the actor has processed. Expiry is judged against it rather than
 * {@code System.currentTimeMillis()} so that replaying journaled commands during
 * recovery yields the same expiry decisions it did live — a wall-clock read would
 * make holds look expired at replay time and drop bookings the commands actually
 * confirmed.
 */
public record ShowState(
        Set<String> booked,
        Map<String, SeatHold> holdsById,
        Map<String, String> seatToHoldId,
        long clockEpochMs) implements Serializable {

    /** A brand-new show with nothing booked or held. */
    public static ShowState empty() {
        return new ShowState(new LinkedHashSet<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), 0L);
    }

    /** A copy whose logical clock has advanced to {@code now} (never moves backwards). */
    public ShowState withClock(long now) {
        long advanced = Math.max(clockEpochMs, now);
        return advanced == clockEpochMs
                ? this
                : new ShowState(booked, holdsById, seatToHoldId, advanced);
    }

    /** True if the seat is booked or currently held by anyone. */
    public boolean isUnavailable(String seatId) {
        return booked.contains(seatId) || seatToHoldId.containsKey(seatId);
    }

    /** A copy with {@code hold} added and its seats indexed. */
    public ShowState withHold(SeatHold hold) {
        Map<String, SeatHold> nextHolds = new LinkedHashMap<>(holdsById);
        nextHolds.put(hold.holdId(), hold);
        Map<String, String> nextIndex = new LinkedHashMap<>(seatToHoldId);
        for (String seatId : hold.seatIds()) {
            nextIndex.put(seatId, hold.holdId());
        }
        return new ShowState(booked, nextHolds, nextIndex, clockEpochMs);
    }

    /** A copy with the hold's seats promoted to {@code booked} and the hold removed. */
    public ShowState withHoldConfirmed(SeatHold hold) {
        Set<String> nextBooked = new LinkedHashSet<>(booked);
        nextBooked.addAll(hold.seatIds());
        return new ShowState(nextBooked, holdsById, seatToHoldId, clockEpochMs).withoutHold(hold.holdId());
    }

    /** A copy with the given hold (if any) removed and its seats freed. */
    public ShowState withoutHold(String holdId) {
        SeatHold hold = holdsById.get(holdId);
        if (hold == null) {
            return this;
        }
        Map<String, SeatHold> nextHolds = new LinkedHashMap<>(holdsById);
        nextHolds.remove(holdId);
        Map<String, String> nextIndex = new LinkedHashMap<>(seatToHoldId);
        for (String seatId : hold.seatIds()) {
            nextIndex.remove(seatId, holdId);
        }
        return new ShowState(booked, nextHolds, nextIndex, clockEpochMs);
    }

    /** A copy with every hold whose window has elapsed dropped (lazy safety net). */
    public ShowState withoutExpired(long now) {
        List<String> expired = new ArrayList<>();
        for (SeatHold hold : holdsById.values()) {
            if (hold.isExpired(now)) {
                expired.add(hold.holdId());
            }
        }
        if (expired.isEmpty()) {
            return this;
        }
        ShowState next = this;
        for (String holdId : expired) {
            next = next.withoutHold(holdId);
        }
        return next;
    }
}
