package io.cinetor.actor;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.StatefulHandler;
import io.cinetor.model.Seats;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Persistent, stateful counterpart of {@link ShowActor}.
 *
 * <p>Same booking semantics — one actor per show, one message at a time, so
 * double-booking is impossible — but the seat state lives in an immutable
 * {@link ShowState} that Cajun can journal and snapshot. Because messages are
 * replayed and state is snapshotted on recovery, a restarted backend rebuilds
 * every show's holds and bookings instead of starting fresh. That durability is
 * the whole point of the stateful mode; the cost is the I/O of writing each
 * command to the journal, which the comparison suite measures.
 *
 * <p>Contrast with {@link ShowActor}:
 * <ul>
 *   <li>{@code ShowActor} implements {@code Handler} and mutates private fields;</li>
 *   <li>{@code StatefulShowActor} implements {@code StatefulHandler} and
 *       <em>returns</em> the next state from every message.</li>
 * </ul>
 * The validation, conflict detection, hold/confirm/release rules and
 * self-scheduled {@link ShowProtocol.ExpireHold} are otherwise identical.
 *
 * <p>{@code validSeatIds} and {@code holdMillis} are deterministic per-show
 * configuration (derived from the show's dimensions), not part of the persisted
 * state — the actor is reconstructed with the same constructor arguments on
 * recovery, so they never need journaling.
 */
public class StatefulShowActor implements StatefulHandler<ShowState, ShowProtocol.Command> {

    private final Set<String> validSeatIds;
    private final long holdMillis;

    public StatefulShowActor(int rows, int cols, long holdMillis) {
        this.validSeatIds = Seats.validIds(rows, cols);
        this.holdMillis = holdMillis;
    }

    @Override
    public ShowState receive(ShowProtocol.Command message, ShowState state, ActorContext context) {
        // Drive time from the command's timestamp, not the wall clock, so replay is
        // deterministic. Reads and self-scheduled expiry carry no timestamp, so they
        // reuse the latest logical time the actor has seen.
        long msgTime = switch (message) {
            case ShowProtocol.Hold hold -> hold.atEpochMs();
            case ShowProtocol.Confirm confirm -> confirm.atEpochMs();
            case ShowProtocol.Release release -> release.atEpochMs();
            case ShowProtocol.GetSnapshot ignored -> state.clockEpochMs();
            case ShowProtocol.ExpireHold ignored -> state.clockEpochMs();
        };
        long now = Math.max(state.clockEpochMs(), msgTime);
        ShowState current = state.withClock(now).withoutExpired(now);
        return switch (message) {
            case ShowProtocol.GetSnapshot ignored -> {
                reply(context, new ShowProtocol.Snapshot(List.copyOf(current.booked())));
                yield current;
            }
            case ShowProtocol.Hold hold -> handleHold(hold, current, context);
            case ShowProtocol.Confirm confirm -> handleConfirm(confirm, current, context);
            case ShowProtocol.Release release -> handleRelease(release, current, context);
            case ShowProtocol.ExpireHold expire -> current.withoutHold(expire.holdId());
        };
    }

    private ShowState handleHold(ShowProtocol.Hold hold, ShowState state, ActorContext context) {
        if (hold.holderId() == null || hold.holderId().isBlank()) {
            reply(context, new ShowProtocol.Rejected("Missing holder id", List.of()));
            return state;
        }
        if (hold.seatIds() == null || hold.seatIds().isEmpty()) {
            reply(context, new ShowProtocol.Rejected("No seats selected", List.of()));
            return state;
        }
        // Collapse duplicate seat ids so a single seat can't be held — or charged — twice.
        List<String> requested = new ArrayList<>(new LinkedHashSet<>(hold.seatIds()));
        if (!validSeatIds.containsAll(requested)) {
            reply(context, new ShowProtocol.Rejected("One or more seats do not exist", List.of()));
            return state;
        }

        // A seat is unavailable if it is already booked or currently held by anyone.
        List<String> conflicts = new ArrayList<>();
        for (String seatId : requested) {
            if (state.isUnavailable(seatId)) {
                conflicts.add(seatId);
            }
        }
        if (!conflicts.isEmpty()) {
            reply(context, new ShowProtocol.Rejected(
                    "Some seats are no longer available", List.copyOf(conflicts)));
            return state;
        }

        // Id and expiry are derived from the command (assigned by BookingService),
        // never from UUID/System.currentTimeMillis() here, so replay rebuilds the
        // identical hold — the later Confirm/Release/ExpireHold reference this id.
        String holdId = hold.holdId();
        long expiresAt = hold.atEpochMs() + holdMillis;
        SeatHold seatHold = new SeatHold(holdId, hold.holderId(), expiresAt, List.copyOf(requested));

        // Schedule automatic release when the payment window elapses. On recovery
        // this re-arms after replaying the Hold, so a rebuilt hold still expires.
        context.tellSelf(new ShowProtocol.ExpireHold(holdId), holdMillis, TimeUnit.MILLISECONDS);

        // NOTE: no SSE broadcast here — a hold must not change the public seat map.
        reply(context, new ShowProtocol.Held(holdId, seatHold.seatIds(), expiresAt));
        return state.withHold(seatHold);
    }

    private ShowState handleConfirm(ShowProtocol.Confirm confirm, ShowState state, ActorContext context) {
        SeatHold hold = state.holdsById().get(confirm.holdId());
        if (hold == null) {
            reply(context, new ShowProtocol.Rejected(
                    "Your seat hold has expired. Please select your seats again.", List.of()));
            return state;
        }
        if (!Objects.equals(hold.holderId(), confirm.holderId())) {
            reply(context, new ShowProtocol.Rejected("This hold belongs to another session", List.of()));
            return state;
        }

        ShowState next = state.withHoldConfirmed(hold);
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        reply(context, new ShowProtocol.Confirmed(
                bookingId, hold.seatIds(), List.copyOf(next.booked())));
        return next;
    }

    private ShowState handleRelease(ShowProtocol.Release release, ShowState state, ActorContext context) {
        SeatHold hold = state.holdsById().get(release.holdId());
        boolean released = hold != null && Objects.equals(hold.holderId(), release.holderId());
        reply(context, new ShowProtocol.Released(released));
        return released ? state.withoutHold(release.holdId()) : state;
    }

    private void reply(ActorContext context, Object response) {
        context.getSender().ifPresent(sender -> context.tell(sender, response));
    }
}
