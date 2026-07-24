package io.cinetor.model;

import java.util.List;

/**
 * Immutable domain records that make up the Cinetor catalogue.
 *
 * <p>The hierarchy is: City -> Movie -> Theatre -> Show. A {@link Show} is the
 * bookable unit and owns a seat layout. All records are plain data and are
 * serialised directly to JSON by Javalin/Jackson.
 */
public final class Catalogue {

    private Catalogue() {
    }

    /** A city the user can browse movies in. */
    public record City(String id, String name) {
    }

    /** A movie in the catalogue. */
    public record Movie(
            String id,
            String title,
            String genre,
            String rating,
            int runtimeMinutes,
            String language,
            String posterColor,
            String synopsis) {
    }

    /** A physical cinema. */
    public record Theatre(String id, String name, String area) {
    }

    /**
     * A single screening. Owns the seat grid dimensions so a seat map can be
     * generated deterministically from the show id.
     */
    public record Show(
            String id,
            String movieId,
            String theatreId,
            String cityId,
            String startTime,
            String screen,
            String format,
            int rows,
            int cols) {
    }

    /** A theatre together with the shows it is running for a given movie. */
    public record TheatreShows(Theatre theatre, List<Show> shows) {
    }
}
