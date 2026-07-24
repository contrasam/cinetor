package io.cinetor.actor;

import java.io.Serializable;
import java.util.List;

/**
 * A temporary reservation of one or more seats by a single user during the
 * payment window. Held seats are blocked from other holds/bookings but are not
 * part of the publicly broadcast seat map.
 *
 * <p>Implements {@link Serializable} because it is part of a
 * {@link io.cinetor.actor.ShowState} snapshot when a show runs as a persistent
 * stateful actor (Cajun snapshots state with Java serialization).
 */
public record SeatHold(String holdId, String holderId, long expiresAtEpochMs, List<String> seatIds)
        implements Serializable {

    public boolean isExpired(long nowEpochMs) {
        return nowEpochMs >= expiresAtEpochMs;
    }
}
