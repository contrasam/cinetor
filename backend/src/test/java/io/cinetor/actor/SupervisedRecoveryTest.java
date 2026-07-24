package io.cinetor.actor;

import com.cajunsystems.Actor;
import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.SupervisionStrategy;
import com.cajunsystems.persistence.BatchedMessageJournal;
import com.cajunsystems.persistence.SnapshotStore;
import com.cajunsystems.runtime.persistence.PersistenceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end demonstration of the supervision + crash-recovery story.
 *
 * <p>The wiring mirrors {@code BookingService}: a persistent {@link StatefulShowActor}
 * is spawned as a child of a supervising {@link TheatreActor}, with a {@code RESTART}
 * supervision strategy. When the show panics, the framework restarts <em>just that
 * show</em>; because the show is persistent, that restart is a <em>recovery</em> —
 * Cajun replays the journal and rebuilds every hold and booking the show had.
 *
 * <p>The scenario:
 * <ol>
 *   <li>Seats A1/A2 are held successfully — a real reservation, journaled and applied.</li>
 *   <li>A fault is armed ({@link FaultInjectable}); the next hold (on a different seat)
 *       panics mid-flight, crashing the actor.</li>
 *   <li>The show's RESTART supervision restarts it and recovery replays the journal.</li>
 *   <li>The A1/A2 reservation survived: a competing hold on A1 is still rejected, and
 *       the original hold still confirms into a booking.</li>
 * </ol>
 *
 * <p>Contrast with {@link ShowActorPanicTest}, which shows the in-memory actor losing
 * its holds on the same fault — no journal, nothing to replay.
 */
class SupervisedRecoveryTest {

    private static final long HOLD_MS = 300_000L;
    // Generous: a cold persistent actor's first message (and its first message after
    // a restart) pays a snapshot-lookup + journal-replay cost of a few seconds.
    private static final Duration ASK = Duration.ofSeconds(8);
    // Cajun's file stores root at ./cajun_persistence regardless of the path passed
    // to the factory, so the demo cleans that directory around itself.
    private static final Path PERSISTENCE_DIR = Path.of("cajun_persistence");

    private ActorSystem system;

    @AfterEach
    void tearDown() throws Exception {
        if (system != null) {
            system.shutdown();
        }
        deleteRecursively(PERSISTENCE_DIR);
    }

    @Test
    void heldSeatsSurviveAMidHoldPanicViaJournalRecovery() throws Exception {
        deleteRecursively(PERSISTENCE_DIR);
        system = new ActorSystem();
        BatchedMessageJournal<ShowProtocol.Command> journal =
                PersistenceFactory.createBatchedFileMessageJournal("journal");
        SnapshotStore<ShowState> snapshots =
                PersistenceFactory.createFileSnapshotStore("snapshots");

        // Unique ids so repeated local runs never replay a previous run's journal.
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // Supervising theatre and its persistent show child (RESTART strategy).
        Pid theatrePid = system.actorOf(new TheatreActor("theatre-" + suffix))
                .withId("theatre-" + suffix)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .spawn();
        Actor<?> theatre = system.getActor(theatrePid);

        StatefulShowActor handler = new StatefulShowActor(5, 5, HOLD_MS);
        Pid show = system.statefulActorOf(handler, ShowState.empty())
                .withId("show-" + suffix)
                .withPersistence(journal, snapshots)
                .withParent(theatre)
                .withSupervisionStrategy(SupervisionStrategy.RESTART)
                .spawn();

        // Warm the cold persistent actor (first message replays/initialises state).
        awaitSnapshot(show);

        // 1) A genuine reservation we expect to survive the crash.
        String holderId = "ada-session";
        String keepHoldId = "HOLD-KEEP";
        Object held = ask(show,
                new ShowProtocol.Hold(List.of("A1", "A2"), holderId, keepHoldId, System.currentTimeMillis()));
        assertInstanceOf(ShowProtocol.Held.class, held, "the initial hold on A1/A2 should succeed");

        // 2) Arm the fault and crash the actor on a *different* hold. The panic
        //    propagates as a failed ask; the show's RESTART supervision then restarts
        //    it and recovery replays the journal.
        handler.armPanic();
        boolean panicked = false;
        try {
            ask(show, new ShowProtocol.Hold(List.of("B1"), "bob", "HOLD-CRASH", System.currentTimeMillis()));
        } catch (Exception expected) {
            panicked = true;
        }
        if (!panicked) {
            fail("the armed hold should have panicked the actor");
        }

        // 3) Wait for the restart+recovery to land: a competing hold on A1 by someone
        //    else must still be rejected, because the recovered A1/A2 hold blocks it.
        awaitRecoveredHoldBlocks(show, "A1");

        // 4) And the original reservation still confirms into a booking.
        Object confirm = ask(show,
                new ShowProtocol.Confirm(keepHoldId, holderId, "Ada", System.currentTimeMillis()));
        assertInstanceOf(ShowProtocol.Confirmed.class, confirm,
                "the hold placed before the crash must still confirm after journal recovery");
        assertEquals(List.of("A1", "A2"), ((ShowProtocol.Confirmed) confirm).seatIds(),
                "the recovered booking must be exactly the seats held before the crash");
    }

    /** Polls a competing hold on {@code seat} until the recovered hold rejects it (or times out). */
    private void awaitRecoveredHoldBlocks(Pid show, String seat) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            Object reply;
            try {
                reply = ask(show, new ShowProtocol.Hold(List.of(seat), "intruder",
                        "HOLD-" + UUID.randomUUID().toString().substring(0, 8), System.currentTimeMillis()));
            } catch (Exception stillRestarting) {
                Thread.sleep(250); // actor mid-restart / re-initialising; retry
                continue;
            }
            if (reply instanceof ShowProtocol.Rejected rejected
                    && rejected.conflictingSeats().contains(seat)) {
                return; // recovered hold is back and blocking the seat
            }
            if (reply instanceof ShowProtocol.Held) {
                fail("seat " + seat + " was handed out after the crash — the hold was NOT recovered");
            }
            Thread.sleep(250);
        }
        fail("timed out waiting for the recovered hold to block seat " + seat);
    }

    /** Warm-up: retry a snapshot read until the cold persistent actor answers. */
    private void awaitSnapshot(Pid show) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Object snap = ask(show, new ShowProtocol.GetSnapshot());
                assertInstanceOf(ShowProtocol.Snapshot.class, snap);
                return;
            } catch (Exception coldStart) {
                // First message pays the state-initialisation cost; try again.
            }
        }
        fail("persistent actor did not initialise in time");
    }

    private Object ask(Pid show, ShowProtocol.Command command) throws Exception {
        return system.ask(show, command, ASK).get(ASK.toMillis() + 500, TimeUnit.MILLISECONDS);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            });
        }
        assertTrue(true); // keep the checked-exception signature honest
    }
}
