package io.cinetor.actor;

/**
 * Message protocol for a {@link TheatreActor}, the supervisory parent of a
 * theatre's per-show seat actors.
 *
 * <p>Deliberately small: the supervision itself — deciding to restart a show that
 * crashed — is handled by the actor framework against the {@code TheatreActor}'s
 * configured supervision strategy, not by delivering a message to its handler.
 * These commands only let the theatre keep, and report, a registry of the shows
 * it is watching, so the supervision layer is observable.
 */
public final class TheatreProtocol {

    private TheatreProtocol() {
    }

    /** Base type for everything a {@link TheatreActor} understands. */
    public sealed interface Command permits Watch, GetStatus {
    }

    /** Register a show under this theatre's supervision (sent when its actor is spawned). */
    public record Watch(String showId) implements Command {
    }

    /** Ask how many shows this theatre is supervising. Replies {@link Status}. */
    public record GetStatus() implements Command {
    }

    /** Reply to {@link GetStatus}: this theatre's id and the shows it supervises. */
    public record Status(String theatreId, int supervisedShows) {
    }
}
