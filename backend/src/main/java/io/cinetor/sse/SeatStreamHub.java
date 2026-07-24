package io.cinetor.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cinetor.metrics.Metrics;
import io.javalin.http.sse.SseClient;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out hub for Server-Sent Events. Browsers viewing a show's seat map open a
 * long-lived SSE connection here; whenever seats are booked the hub pushes the
 * updated booked-seat list to every client watching that show, so the map
 * updates in real time without polling.
 */
public class SeatStreamHub {

    private static final Logger log = LoggerFactory.getLogger(SeatStreamHub.class);

    private final Map<String, Set<SseClient>> clientsByShow = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private final Counter broadcasts;

    public SeatStreamHub(Metrics metrics) {
        metrics.gauge("cinetor.sse.clients", "Connected SSE clients across all shows",
                this, SeatStreamHub::totalClients);
        this.broadcasts = Counter.builder("cinetor.sse.broadcasts")
                .description("Seat-update broadcasts fanned out to SSE clients")
                .register(metrics.registry());
    }

    private double totalClients() {
        return clientsByShow.values().stream().mapToInt(Set::size).sum();
    }

    /** Registers a client for a show and keeps its connection open. */
    public void register(String showId, SseClient client) {
        client.keepAlive();
        clientsByShow.computeIfAbsent(showId, k -> ConcurrentHashMap.newKeySet()).add(client);
        client.onClose(() -> remove(showId, client));
        log.info("SSE client subscribed to show {} ({} watching)", showId, watchers(showId));
    }

    /** Sends the current booked-seat list to a single client (used on connect). */
    public void sendSnapshot(SseClient client, List<String> bookedSeats) {
        emit(client, bookedSeats);
    }

    /** Pushes the updated booked-seat list to everyone watching the show. */
    public void broadcast(String showId, List<String> bookedSeats) {
        Set<SseClient> clients = clientsByShow.get(showId);
        if (clients == null || clients.isEmpty()) {
            return;
        }
        for (SseClient client : clients) {
            emit(client, bookedSeats);
        }
        broadcasts.increment();
        log.info("Broadcast seat update for show {} to {} client(s)", showId, clients.size());
    }

    private void emit(SseClient client, List<String> bookedSeats) {
        try {
            String payload = json.writeValueAsString(new SeatUpdate(bookedSeats));
            client.sendEvent("seats-update", payload);
        } catch (Exception e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private void remove(String showId, SseClient client) {
        Set<SseClient> clients = clientsByShow.get(showId);
        if (clients != null) {
            clients.remove(client);
        }
    }

    private int watchers(String showId) {
        Set<SseClient> clients = clientsByShow.get(showId);
        return clients == null ? 0 : clients.size();
    }

    /** Wire payload for a seat update. */
    public record SeatUpdate(List<String> booked) {
    }
}
