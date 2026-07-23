package io.cinetor.catalog;

import io.cinetor.model.Catalogue.City;
import io.cinetor.model.Catalogue.Movie;
import io.cinetor.model.Catalogue.Show;
import io.cinetor.model.Catalogue.Theatre;
import io.cinetor.model.Catalogue.TheatreShows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory demo catalogue: cities, movies, theatres and the shows that tie
 * them together. This is intentionally static seed data — the interesting,
 * stateful part of the system is the live seat booking handled by actors.
 */
public final class CatalogueData {

    private final List<City> cities = new ArrayList<>();
    private final Map<String, Movie> movies = new LinkedHashMap<>();
    private final Map<String, Theatre> theatres = new LinkedHashMap<>();
    private final Map<String, Show> shows = new LinkedHashMap<>();

    public CatalogueData() {
        seed();
    }

    private void seed() {
        cities.add(new City("blr", "Bangalore"));
        cities.add(new City("bom", "Mumbai"));
        cities.add(new City("hyd", "Hyderabad"));

        addMovie(new Movie("interstellar", "Interstellar", "Sci-Fi", "PG-13", 169, "English",
                "#6366f1", "A team of explorers travel through a wormhole in search of a new home for humanity."));
        addMovie(new Movie("dune-two", "Dune: Part Two", "Sci-Fi", "PG-13", 166, "English",
                "#d97706", "Paul Atreides unites with the Fremen to wage war against House Harkonnen."));
        addMovie(new Movie("oppenheimer", "Oppenheimer", "Drama", "R", 180, "English",
                "#dc2626", "The story of J. Robert Oppenheimer and the making of the atomic bomb."));
        addMovie(new Movie("spirited", "Spirited Away", "Animation", "PG", 125, "Japanese",
                "#0891b2", "A young girl wanders into a world of spirits and must find a way to free her parents."));
        addMovie(new Movie("grand-budapest", "The Grand Budapest Hotel", "Comedy", "R", 99, "English",
                "#db2777", "A concierge and his protege become embroiled in the theft of a priceless painting."));

        addTheatre(new Theatre("prestige-blr", "Prestige IMAX", "Koramangala"));
        addTheatre(new Theatre("orion-blr", "Orion Cineplex", "Rajajinagar"));
        addTheatre(new Theatre("pvr-bom", "PVR Icon", "Lower Parel"));
        addTheatre(new Theatre("regal-bom", "Regal Cinema", "Colaba"));
        addTheatre(new Theatre("prasads-hyd", "Prasads Multiplex", "Necklace Road"));

        // Bangalore
        addShows("blr", "interstellar", "prestige-blr", "IMAX", "Screen 1", 10, 14,
                "10:30", "14:00", "18:30", "22:00");
        addShows("blr", "dune-two", "prestige-blr", "IMAX", "Screen 2", 9, 16,
                "11:15", "15:30", "20:45");
        addShows("blr", "oppenheimer", "orion-blr", "2D", "Audi 3", 8, 12,
                "12:00", "16:15", "20:30");
        addShows("blr", "grand-budapest", "orion-blr", "2D", "Audi 1", 7, 10,
                "13:00", "18:00");

        // Mumbai
        addShows("bom", "dune-two", "pvr-bom", "4DX", "Screen 5", 8, 12,
                "10:00", "13:45", "17:30", "21:15");
        addShows("bom", "oppenheimer", "pvr-bom", "2D", "Screen 2", 9, 14,
                "11:30", "16:00", "20:15");
        addShows("bom", "interstellar", "regal-bom", "70mm", "Grand Hall", 10, 16,
                "14:30", "19:45");
        addShows("bom", "spirited", "regal-bom", "2D", "Studio", 6, 10,
                "12:15", "17:00");

        // Hyderabad
        addShows("hyd", "oppenheimer", "prasads-hyd", "IMAX", "Screen 1", 10, 16,
                "10:45", "15:00", "19:15", "22:30");
        addShows("hyd", "interstellar", "prasads-hyd", "2D", "Audi 4", 8, 12,
                "13:30", "18:45");
        addShows("hyd", "spirited", "prasads-hyd", "2D", "Audi 2", 6, 10,
                "11:00", "16:30");
    }

    private void addMovie(Movie movie) {
        movies.put(movie.id(), movie);
    }

    private void addTheatre(Theatre theatre) {
        theatres.put(theatre.id(), theatre);
    }

    private void addShows(String cityId, String movieId, String theatreId, String format,
                          String screen, int rows, int cols, String... times) {
        for (String time : times) {
            String id = String.join("-", cityId, movieId, theatreId, time.replace(":", ""));
            shows.put(id, new Show(id, movieId, theatreId, cityId, time, screen, format, rows, cols));
        }
    }

    public List<City> cities() {
        return List.copyOf(cities);
    }

    public Optional<Movie> movie(String movieId) {
        return Optional.ofNullable(movies.get(movieId));
    }

    public Optional<Theatre> theatre(String theatreId) {
        return Optional.ofNullable(theatres.get(theatreId));
    }

    public Optional<Show> show(String showId) {
        return Optional.ofNullable(shows.get(showId));
    }

    /** Distinct movies that have at least one show in the given city. */
    public List<Movie> moviesInCity(String cityId) {
        List<Movie> result = new ArrayList<>();
        for (Movie movie : movies.values()) {
            boolean playing = shows.values().stream()
                    .anyMatch(s -> s.cityId().equals(cityId) && s.movieId().equals(movie.id()));
            if (playing) {
                result.add(movie);
            }
        }
        return result;
    }

    /** Shows for a movie in a city, grouped by theatre. */
    public List<TheatreShows> theatreShows(String cityId, String movieId) {
        Map<String, List<Show>> byTheatre = new LinkedHashMap<>();
        shows.values().stream()
                .filter(s -> s.cityId().equals(cityId) && s.movieId().equals(movieId))
                .forEach(s -> byTheatre.computeIfAbsent(s.theatreId(), k -> new ArrayList<>()).add(s));

        List<TheatreShows> result = new ArrayList<>();
        byTheatre.forEach((theatreId, theatreShows) ->
                theatre(theatreId).ifPresent(t -> result.add(new TheatreShows(t, theatreShows))));
        return result;
    }
}
