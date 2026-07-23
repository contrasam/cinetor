package io.cinetor.web;

import io.cinetor.model.Catalogue.Movie;
import io.cinetor.model.Catalogue.Show;
import io.cinetor.model.Catalogue.Theatre;
import io.cinetor.model.Seats.Seat;

import java.util.List;

/** Request/response shapes for the HTTP API. */
public final class Dtos {

    private Dtos() {
    }

    /** Full detail needed to render a seat-selection screen. */
    public record ShowDetail(
            Show show,
            Movie movie,
            Theatre theatre,
            int rows,
            int cols,
            List<Seat> seats,
            List<String> bookedSeats) {
    }

    // --- Hold (phase 1) ----------------------------------------------------

    /** Body of POST /api/shows/{id}/hold. */
    public record HoldRequest(List<String> seatIds, String holderId) {
    }

    /** Response for a hold attempt. */
    public record HoldResponse(
            String status,
            String holdId,
            List<String> seats,
            long expiresAt,
            int ttlSeconds,
            int totalPrice,
            String reason,
            List<String> conflictingSeats) {

        public static HoldResponse held(String holdId, List<String> seats, long expiresAt,
                                        int ttlSeconds, int totalPrice) {
            return new HoldResponse("HELD", holdId, seats, expiresAt, ttlSeconds, totalPrice, null, null);
        }

        public static HoldResponse rejected(String reason, List<String> conflicts) {
            return new HoldResponse("REJECTED", null, null, 0, 0, 0, reason, conflicts);
        }
    }

    // --- Confirm (phase 2) -------------------------------------------------

    /** Body of POST /api/shows/{id}/confirm. */
    public record ConfirmRequest(String holdId, String holderId, String customerName) {
    }

    /** Response for a booking confirmation. */
    public record BookingResponse(
            String status,
            String bookingId,
            String customerName,
            List<String> seats,
            int totalPrice,
            List<String> bookedSeats,
            String reason,
            List<String> conflictingSeats) {

        public static BookingResponse confirmed(String bookingId, String customerName,
                                                List<String> seats, int totalPrice,
                                                List<String> bookedSeats) {
            return new BookingResponse("CONFIRMED", bookingId, customerName, seats, totalPrice,
                    bookedSeats, null, null);
        }

        public static BookingResponse rejected(String reason, List<String> conflicts) {
            return new BookingResponse("REJECTED", null, null, null, 0, null, reason, conflicts);
        }
    }

    // --- Release -----------------------------------------------------------

    /** Body of POST /api/shows/{id}/release. */
    public record ReleaseRequest(String holdId, String holderId) {
    }
}
