package io.cinetor.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Central metrics registry, exposed for scraping at {@code GET /api/metrics}.
 *
 * <p>The signals that matter most under load are the ones a client-side load
 * tester cannot see: how long the seat actor takes to answer (the value that
 * trips the 5s ask timeout in {@code BookingService}), how many holds turn into
 * bookings versus rejections, and how many SSE clients are connected. Those are
 * modelled here as timers, counters and gauges; JVM/process metrics are bound
 * too so memory and GC can be correlated with request latency.
 */
public final class Metrics {

    private final PrometheusMeterRegistry registry;

    // Meters are name+tag unique, so cache them instead of rebuilding per call.
    private final ConcurrentHashMap<String, Timer> askTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> askErrors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> outcomes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> httpTimers = new ConcurrentHashMap<>();

    public Metrics() {
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }

    /** Prometheus text exposition for the scrape endpoint. */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Timer for one actor ask, tagged by operation
     * ({@code hold|confirm|release|snapshot}). This is the seat actor's mailbox
     * wait + processing time — the clearest server-side saturation signal.
     */
    public Timer askTimer(String op) {
        return askTimers.computeIfAbsent(op, o -> Timer.builder("cinetor.actor.ask")
                .description("Latency of a single seat-actor ask")
                .tag("op", o)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    /** Count of asks that failed or timed out (these surface to the client as HTTP 500). */
    public Counter askErrors(String op) {
        return askErrors.computeIfAbsent(op, o -> Counter.builder("cinetor.actor.ask.errors")
                .description("Actor asks that failed or timed out")
                .tag("op", o)
                .register(registry));
    }

    /**
     * Business-outcome counter, e.g. {@code outcome("hold", "held")} or
     * {@code outcome("confirm", "rejected")}.
     */
    public Counter outcome(String flow, String result) {
        return outcomes.computeIfAbsent(flow + ':' + result, k -> Counter.builder("cinetor." + flow)
                .description("Booking-flow outcomes")
                .tag("result", result)
                .register(registry));
    }

    /** Per-endpoint HTTP timer, tagged by method, matched route template and status class. */
    public Timer httpTimer(String method, String route, String statusClass) {
        String key = method + ' ' + route + ' ' + statusClass;
        return httpTimers.computeIfAbsent(key, k -> Timer.builder("cinetor.http.server.requests")
                .description("HTTP request latency")
                .tag("method", method)
                .tag("route", route)
                .tag("status", statusClass)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    /** Registers a gauge that reports a live value (e.g. connected SSE clients). */
    public <T> void gauge(String name, String description, T obj, ToDoubleFunction<T> value) {
        Gauge.builder(name, obj, value).description(description).register(registry);
    }

    /**
     * A constant info gauge advertising the actor mode the backend is running in
     * ({@code memory | stateful | stateful-backpressure}), so a metrics scrape
     * alone tells you which configuration produced the numbers.
     */
    public void registerModeInfo(String mode) {
        Gauge.builder("cinetor.actor.mode.info", () -> 1.0)
                .description("Active actor mode (value is always 1; read the 'mode' tag)")
                .tag("mode", mode)
                .register(registry);
    }
}
