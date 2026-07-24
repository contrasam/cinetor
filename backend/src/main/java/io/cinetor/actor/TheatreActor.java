package io.cinetor.actor;

import com.cajunsystems.ActorContext;
import com.cajunsystems.handler.Handler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Supervisory parent for one theatre's shows.
 *
 * <p>Every per-show seat actor is spawned as a <em>child</em> of the theatre it
 * belongs to, with a supervision strategy that {@code ESCALATE}s failures to this
 * actor. When a {@link ShowActor}/{@link StatefulShowActor} panics — see
 * {@link FaultInjectable} — the framework hands the failure to this parent, whose
 * own strategy is {@code RESTART}: it stops and restarts just the crashed show,
 * leaving its siblings untouched. For a persistent ({@code stateful}) show that
 * restart is a recovery — the actor replays its journal and rebuilds every hold
 * and booking — which is what turns a mid-hold crash into a non-event for the
 * seats that were already reserved.
 *
 * <p>The handler itself does no supervision work (the framework does, against the
 * strategy configured on this actor); it just maintains a registry of the shows
 * under its wing so the layer is observable via {@link TheatreProtocol.GetStatus}.
 */
public class TheatreActor implements Handler<TheatreProtocol.Command> {

    private final String theatreId;
    private final Set<String> supervised = new LinkedHashSet<>();

    public TheatreActor(String theatreId) {
        this.theatreId = theatreId;
    }

    @Override
    public void receive(TheatreProtocol.Command message, ActorContext context) {
        switch (message) {
            case TheatreProtocol.Watch watch -> {
                supervised.add(watch.showId());
                context.getLogger().info("Theatre {} now supervising {} show(s)",
                        theatreId, supervised.size());
            }
            case TheatreProtocol.GetStatus ignored -> context.getSender().ifPresent(sender ->
                    context.tell(sender, new TheatreProtocol.Status(theatreId, supervised.size())));
        }
    }

    /** Read-only view of the shows this theatre supervises (test/introspection aid). */
    public List<String> supervisedShows() {
        return List.copyOf(supervised);
    }
}
