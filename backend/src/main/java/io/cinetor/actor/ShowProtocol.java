package io.cinetor.actor;

import java.io.Serializable;
import java.util.List;

/**
 * Message and reply protocol for a {@link ShowActor}.
 *
 * <p>Booking is a two-phase flow:
 * <ol>
 *   <li>{@link Hold} reserves seats for one user for a few minutes while they
 *       "pay". A hold blocks other users from holding/booking those seats, but
 *       is deliberately <em>not</em> broadcast over SSE.</li>
 *   <li>{@link Confirm} turns a hold into a real booking. Only this step
 *       changes the publicly visible seat map (and triggers an SSE broadcast).</li>
 * </ol>
 * A hold that is neither confirmed nor {@link Release released} expires on its
 * own via a self-scheduled {@link ExpireHold} message.
 */
public final class ShowProtocol {

    private ShowProtocol() {
    }

    /**
     * Base type for everything a ShowActor understands.
     *
     * <p>Extends {@link Serializable} so commands can be written to the message
     * journal when a show runs as a persistent stateful actor. The in-memory
     * mode never serialises them; the cost only applies to the stateful modes.
     */
    public sealed interface Command extends Serializable
            permits GetSnapshot, Hold, Confirm, Release, ExpireHold {
    }

    /** Ask for the current set of <em>booked</em> seats (holds are not included). */
    public record GetSnapshot() implements Command {
    }

    /**
     * Reserve seats for {@code holderId} for the hold window. Replies
     * {@link Held} or {@link Rejected}.
     *
     * <p>{@code holdId} and {@code atEpochMs} are assigned by the caller
     * ({@code BookingService}), not inside the actor. That is deliberate: a
     * stateful actor recovers by <em>replaying</em> these commands through its
     * handler, so anything the handler derives from {@code UUID.randomUUID()} or
     * the wall clock would differ on replay and desynchronise the reconstructed
     * hold from the later {@link Confirm}/{@link Release}/{@link ExpireHold} that
     * reference it. Carrying the id and timestamp on the command keeps replay
     * deterministic.
     */
    public record Hold(List<String> seatIds, String holderId, String holdId, long atEpochMs)
            implements Command {
    }

    /** Turn a hold into a booking. {@code atEpochMs} is the request time (see {@link Hold}). */
    public record Confirm(String holdId, String holderId, String customerName, long atEpochMs)
            implements Command {
    }

    /** Manually release a hold (e.g. the user cancelled). {@code atEpochMs} is the request time. */
    public record Release(String holdId, String holderId, long atEpochMs) implements Command {
    }

    /** Self-scheduled message that fires when a hold's window elapses. No reply. */
    public record ExpireHold(String holdId) implements Command {
    }

    // --- Replies -----------------------------------------------------------

    /** Current set of booked seat ids for the show. */
    public record Snapshot(List<String> bookedSeats) {
    }

    /** A successful hold, with the id used to confirm/release it and its expiry. */
    public record Held(String holdId, List<String> seatIds, long expiresAtEpochMs) {
    }

    /** A successful booking. Contains the seats reserved and the full booked set. */
    public record Confirmed(String bookingId, List<String> seatIds, List<String> allBookedSeats) {
    }

    /** A rejected hold or confirm, e.g. a seat was taken or a hold expired. */
    public record Rejected(String reason, List<String> conflictingSeats) {
    }

    /** Result of a release request. */
    public record Released(boolean released) {
    }
}
