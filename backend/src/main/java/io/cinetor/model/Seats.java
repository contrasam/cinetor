package io.cinetor.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Seat layout generation and seat metadata.
 *
 * <p>Given a show's grid dimensions we deterministically produce a list of
 * seats. Rows are lettered (A, B, C ...) from the screen backwards and seats
 * are numbered from 1. Tier and price are derived from the row so the front of
 * the hall is cheaper than the recliner rows at the back.
 */
public final class Seats {

    private Seats() {
    }

    public enum Tier {
        REGULAR(180),
        PREMIUM(260),
        RECLINER(420);

        public final int price;

        Tier(int price) {
            this.price = price;
        }
    }

    /** A single seat in the hall. */
    public record Seat(String id, String row, int number, Tier tier, int price) {
    }

    /**
     * Builds the seat grid for the given dimensions. The last two rows are
     * recliners, the middle band is premium and the first two rows are regular.
     */
    public static List<Seat> layout(int rows, int cols) {
        List<Seat> seats = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            String rowLabel = String.valueOf((char) ('A' + r));
            Tier tier = tierForRow(r, rows);
            for (int c = 1; c <= cols; c++) {
                String id = rowLabel + c;
                seats.add(new Seat(id, rowLabel, c, tier, tier.price));
            }
        }
        return seats;
    }

    /** The set of all valid seat ids for a layout, used for validation. */
    public static Set<String> validIds(int rows, int cols) {
        Set<String> ids = new LinkedHashSet<>();
        for (Seat seat : layout(rows, cols)) {
            ids.add(seat.id());
        }
        return ids;
    }

    private static Tier tierForRow(int rowIndex, int totalRows) {
        if (rowIndex >= totalRows - 2) {
            return Tier.RECLINER;
        }
        if (rowIndex < 2) {
            return Tier.REGULAR;
        }
        return Tier.PREMIUM;
    }
}
