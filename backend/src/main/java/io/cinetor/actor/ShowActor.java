package io.cinetor.actor;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;
import io.cinetor.model.Seats;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One {@code ShowActor} exists per screening and owns that show's seat state.
 *
 * <p>A Cajun actor processes its mailbox one message at a time, so all reads
 * and writes to {@link #booked} happen on a single thread. That is what makes
 * double-booking impossible here — no locks, no CAS, just the actor's
 * sequential message processing. The seat set is private mutable state
 * encapsulated by the actor, the classic actor-model approach.
 */
public class ShowActor implements Handler<ShowProtocol.Command> {

    private final Set<String> validSeatIds;
    private final Set<String> booked = new LinkedHashSet<>();

    public ShowActor(int rows, int cols) {
        this.validSeatIds = Seats.validIds(rows, cols);
    }

    @Override
    public void receive(ShowProtocol.Command message, ActorContext context) {
        switch (message) {
            case ShowProtocol.GetSnapshot ignored ->
                    reply(context, new ShowProtocol.Snapshot(List.copyOf(booked)));
            case ShowProtocol.Book book -> handleBook(book, context);
        }
    }

    private void handleBook(ShowProtocol.Book book, ActorContext context) {
        List<String> requested = book.seatIds();

        if (requested == null || requested.isEmpty()) {
            reply(context, new ShowProtocol.Rejected("No seats selected", List.of()));
            return;
        }
        if (!validSeatIds.containsAll(requested)) {
            reply(context, new ShowProtocol.Rejected("One or more seats do not exist", List.of()));
            return;
        }

        // Any requested seat already booked -> reject the whole request.
        List<String> conflicts = new ArrayList<>();
        for (String seatId : requested) {
            if (booked.contains(seatId)) {
                conflicts.add(seatId);
            }
        }
        if (!conflicts.isEmpty()) {
            reply(context, new ShowProtocol.Rejected(
                    "Some seats were just booked by someone else", List.copyOf(conflicts)));
            return;
        }

        booked.addAll(requested);
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        reply(context, new ShowProtocol.Confirmed(
                bookingId, List.copyOf(requested), List.copyOf(booked)));
    }

    private void reply(ActorContext context, Object response) {
        context.getSender().ifPresent(sender -> context.tell(sender, response));
    }
}
