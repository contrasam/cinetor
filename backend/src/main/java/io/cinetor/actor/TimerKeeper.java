package io.cinetor.actor;

import com.cajunsystems.ActorContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Keeps Cajun timed messages in sync with a set of durable deadlines.
 *
 * <p>Cajun's {@code tellSelf(msg, delay)} timers live only in the JVM scheduler —
 * a {@code ScheduledExecutorService} and an in-memory map of {@code ScheduledFuture}s —
 * so every pending timer is lost on restart. The <em>durable</em> half of a timer
 * is its deadline, which an actor already persists in its state (here, each
 * hold's {@code expiresAtEpochMs}). This keeper treats the scheduled timers as a
 * cache of those deadlines and reconciles the two after every message:
 *
 * <ul>
 *   <li>a deadline with no timer yet → schedule one for its <em>remaining</em>
 *       time (0 if already past, so it fires at once);</li>
 *   <li>a timer whose deadline is gone → forget it (a stray timed message that
 *       still fires is a no-op at the handler).</li>
 * </ul>
 *
 * <p>The set of armed keys is per-process (transient): a restart starts empty, so
 * the first reconcile after recovery re-arms every restored deadline — whether it
 * came back via journal replay or a snapshot. There is no separate "on recovery"
 * path; recovery is just the case where the cache is cold. That is what makes a
 * non-durable scheduler behave like a durable one, without the scheduler itself
 * needing to persist anything.
 *
 * @param <K> the deadline key type (a hold id, for the seat actors)
 */
final class TimerKeeper<K> {

    private final Set<K> armed = new HashSet<>();

    /**
     * Ensure exactly one in-flight timed message per current deadline. Idempotent
     * and cheap — intended to be called at the end of every message.
     *
     * @param deadlines current durable deadlines (epoch ms) keyed by {@code K}
     * @param context   actor context used to schedule the timed message
     * @param toMessage  builds the self-message to fire for a given key
     */
    void reconcile(Map<K, Long> deadlines, ActorContext context, Function<K, Object> toMessage) {
        long now = System.currentTimeMillis();
        for (Map.Entry<K, Long> entry : deadlines.entrySet()) {
            if (armed.add(entry.getKey())) {
                long delayMs = Math.max(0L, entry.getValue() - now);
                context.tellSelf(toMessage.apply(entry.getKey()), delayMs, TimeUnit.MILLISECONDS);
            }
        }
        // Forget deadlines that are no longer present so the set stays bounded.
        armed.retainAll(deadlines.keySet());
    }
}
