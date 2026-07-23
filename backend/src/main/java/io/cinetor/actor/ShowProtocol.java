package io.cinetor.actor;

import java.util.List;

/**
 * Message and reply protocol for a {@link ShowActor}.
 *
 * <p>Requests are sent via the ask pattern; the actor replies to the caller's
 * sender pid with one of the reply records.
 */
public final class ShowProtocol {

    private ShowProtocol() {
    }

    /** Base type for everything a ShowActor understands. */
    public sealed interface Command permits GetSnapshot, Book {
    }

    /** Ask for the current booking state of the show. Replies with {@link Snapshot}. */
    public record GetSnapshot() implements Command {
    }

    /**
     * Attempt to book a set of seats. Replies with {@link Confirmed} when every
     * requested seat was free, or {@link Rejected} otherwise.
     */
    public record Book(List<String> seatIds, String customerName) implements Command {
    }

    /** Current set of booked seat ids for the show. */
    public record Snapshot(List<String> bookedSeats) {
    }

    /** Base type for the outcome of a {@link Book} request. */
    public sealed interface BookResult permits Confirmed, Rejected {
    }

    /** A successful booking. Contains the seats that were just reserved. */
    public record Confirmed(String bookingId, List<String> seatIds, List<String> allBookedSeats)
            implements BookResult {
    }

    /** A rejected booking, e.g. because a seat was taken in the meantime. */
    public record Rejected(String reason, List<String> conflictingSeats) implements BookResult {
    }
}
