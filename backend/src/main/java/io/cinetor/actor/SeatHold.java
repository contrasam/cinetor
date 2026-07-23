package io.cinetor.actor;

import java.util.List;

/**
 * A temporary reservation of one or more seats by a single user during the
 * payment window. Held seats are blocked from other holds/bookings but are not
 * part of the publicly broadcast seat map.
 */
public record SeatHold(String holdId, String holderId, long expiresAtEpochMs, List<String> seatIds) {

    public boolean isExpired(long nowEpochMs) {
        return nowEpochMs >= expiresAtEpochMs;
    }
}
