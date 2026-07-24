package io.cinetor.actor;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import io.cinetor.model.Seats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One {@code ShowActor} exists per screening and owns that show's seat state.
 *
 * <p>A Cajun actor processes its mailbox one message at a time, so every read
 * and write of the seat state happens on a single thread. That is what makes
 * double-booking impossible here — no locks, just the actor's sequential
 * message processing.
 *
 * <p>The actor tracks two things:
 * <ul>
 *   <li><b>booked</b> — permanently reserved seats. Only this set is exposed via
 *       {@link ShowProtocol.Snapshot} and drives the live SSE seat map.</li>
 *   <li><b>holds</b> — short-lived reservations during payment. They block other
 *       users from grabbing the same seats but are intentionally invisible to
 *       the public seat map. Each hold auto-expires via a self-scheduled
 *       {@link ShowProtocol.ExpireHold} message.</li>
 * </ul>
 */
public class ShowActor implements Handler<ShowProtocol.Command> {

    private final Set<String> validSeatIds;
    private final long holdMillis;

    private final Set<String> booked = new LinkedHashSet<>();
    private final Map<String, SeatHold> holdsById = new HashMap<>();
    private final Map<String, String> seatToHoldId = new HashMap<>();

    public ShowActor(int rows, int cols, long holdMillis) {
        this.validSeatIds = Seats.validIds(rows, cols);
        this.holdMillis = holdMillis;
    }

    @Override
    public void receive(ShowProtocol.Command message, ActorContext context) {
        long now = System.currentTimeMillis();
        purgeExpired(now);
        switch (message) {
            case ShowProtocol.GetSnapshot ignored ->
                    reply(context, new ShowProtocol.Snapshot(List.copyOf(booked)));
            case ShowProtocol.Hold hold -> handleHold(hold, now, context);
            case ShowProtocol.Confirm confirm -> handleConfirm(confirm, context);
            case ShowProtocol.Release release -> handleRelease(release, context);
            case ShowProtocol.ExpireHold expire -> removeHold(expire.holdId());
        }
    }

    private void handleHold(ShowProtocol.Hold hold, long now, ActorContext context) {
        if (hold.holderId() == null || hold.holderId().isBlank()) {
            reply(context, new ShowProtocol.Rejected("Missing holder id", List.of()));
            return;
        }
        if (hold.seatIds() == null || hold.seatIds().isEmpty()) {
            reply(context, new ShowProtocol.Rejected("No seats selected", List.of()));
            return;
        }
        // Collapse duplicate seat ids so a single seat can't be held — or charged — twice.
        List<String> requested = new ArrayList<>(new LinkedHashSet<>(hold.seatIds()));
        if (!validSeatIds.containsAll(requested)) {
            reply(context, new ShowProtocol.Rejected("One or more seats do not exist", List.of()));
            return;
        }

        // A seat is unavailable if it is already booked or currently held by anyone.
        List<String> conflicts = new ArrayList<>();
        for (String seatId : requested) {
            if (booked.contains(seatId) || seatToHoldId.containsKey(seatId)) {
                conflicts.add(seatId);
            }
        }
        if (!conflicts.isEmpty()) {
            reply(context, new ShowProtocol.Rejected(
                    "Some seats are no longer available", List.copyOf(conflicts)));
            return;
        }

        // Id and expiry come from the command (minted by BookingService) so the
        // in-memory and stateful actors behave identically and replay is safe.
        String holdId = hold.holdId();
        long expiresAt = hold.atEpochMs() + holdMillis;
        SeatHold seatHold = new SeatHold(holdId, hold.holderId(), expiresAt, List.copyOf(requested));
        holdsById.put(holdId, seatHold);
        requested.forEach(seatId -> seatToHoldId.put(seatId, holdId));

        // Schedule automatic release for the time remaining until expiry (matches
        // StatefulShowActor; for a fresh live hold this is simply holdMillis).
        long delayMs = Math.max(0, expiresAt - System.currentTimeMillis());
        context.tellSelf(new ShowProtocol.ExpireHold(holdId), delayMs, TimeUnit.MILLISECONDS);

        // NOTE: no SSE broadcast here — a hold must not change the public seat map.
        reply(context, new ShowProtocol.Held(holdId, seatHold.seatIds(), expiresAt));
    }

    private void handleConfirm(ShowProtocol.Confirm confirm, ActorContext context) {
        SeatHold hold = holdsById.get(confirm.holdId());
        if (hold == null) {
            reply(context, new ShowProtocol.Rejected(
                    "Your seat hold has expired. Please select your seats again.", List.of()));
            return;
        }
        if (!Objects.equals(hold.holderId(), confirm.holderId())) {
            reply(context, new ShowProtocol.Rejected("This hold belongs to another session", List.of()));
            return;
        }

        booked.addAll(hold.seatIds());
        removeHold(hold.holdId());

        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        reply(context, new ShowProtocol.Confirmed(
                bookingId, hold.seatIds(), List.copyOf(booked)));
    }

    private void handleRelease(ShowProtocol.Release release, ActorContext context) {
        SeatHold hold = holdsById.get(release.holdId());
        boolean released = hold != null && Objects.equals(hold.holderId(), release.holderId());
        if (released) {
            removeHold(release.holdId());
        }
        reply(context, new ShowProtocol.Released(released));
    }

    /** Drops any holds whose window has elapsed (lazy safety net alongside ExpireHold). */
    private void purgeExpired(long now) {
        List<String> expired = new ArrayList<>();
        for (SeatHold hold : holdsById.values()) {
            if (hold.isExpired(now)) {
                expired.add(hold.holdId());
            }
        }
        expired.forEach(this::removeHold);
    }

    private void removeHold(String holdId) {
        SeatHold hold = holdsById.remove(holdId);
        if (hold != null) {
            for (String seatId : hold.seatIds()) {
                seatToHoldId.remove(seatId, holdId);
            }
        }
    }

    private void reply(ActorContext context, Object response) {
        context.getSender().ifPresent(sender -> context.tell(sender, response));
    }
}
