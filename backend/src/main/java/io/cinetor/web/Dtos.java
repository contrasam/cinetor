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

    /** Body of POST /api/shows/{id}/book. */
    public record BookingRequest(List<String> seatIds, String customerName) {
    }

    /** Response for a booking attempt. */
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
}
