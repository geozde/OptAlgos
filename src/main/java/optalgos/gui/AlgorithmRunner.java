package optalgos.gui;

import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;

import java.time.Duration;
import java.util.List;

/**
 * Runs one {@link AlgorithmChoice} on its own dedicated background thread - the same reasoning as
 * {@code optalgos.benchmark.ThreadTimedExecutor} (clean, unshared thread-of-execution semantics), and
 * reusing one "run off-thread" pattern rather than inventing a GUI-specific one. Progress is exposed
 * through a {@link FrameHistory} that only ever grows (see that class for the bounded-retention
 * guarantee) plus a couple of {@code volatile} fields for live status text - the EDT reads all of it
 * without ever touching the live, still-mutating {@link PackingSolution} the worker thread is
 * operating on, only already-{@code snapshot()}'d copies the history holds.
 */
final class AlgorithmRunner {

    // Matches optalgos.benchmark.BenchmarkRunner: 10s is the spec's own number, confirmed acceptable
    // as a soft target (occasional overrun up to ~12s is fine) rather than a hard wall. A single
    // permutation-neighborhood iteration at n=1000 can itself take over a second (see its class
    // Javadoc), so real runs land in roughly the 10-11.5s range at that scale - comfortably inside
    // the accepted ceiling.
    private static final Duration LOCAL_SEARCH_BUDGET = Duration.ofMillis(10_000);

    private final FrameHistory history = new FrameHistory();
    private volatile boolean finished = false;
    private volatile int liveBoxCount = -1;
    private volatile long liveStepNumber = 0;
    // Tracks whether the MOST RECENTLY reported step (of either kind below) was a finishing step -
    // not just whether one ever happened. That distinction matters specifically for the frame
    // recordFinal below constructs: relaxed-overlap's onPlateau can run its repair rebuild mid-search
    // and then keep searching afterward, so "a repair happened at some point this run" is NOT the same
    // question as "is the run's actual final result a repair rebuild" - only the latter is what the
    // final frame's label should reflect.
    private volatile boolean lastStepWasFinishing = false;

    void start(AlgorithmChoice choice, List<Rectangle> rectangles, int boxLength, long seed) {
        Thread worker = new Thread(() -> {
            FrameConsumer consumer = (liveState, stepNumber) -> {
                lastStepWasFinishing = false;
                liveBoxCount = liveState.boxCount();
                liveStepNumber = stepNumber;
                if (history.shouldKeep(liveState, stepNumber)) {
                    history.record(new Frame(liveState.snapshot(), stepNumber, false, false));
                }
            };
            // Fed only by a neighborhood whose finish()/onPlateau() reports intermediate states of its
            // own (currently just relaxed-overlap's from-scratch repair rebuild) - see AlgorithmChoice.
            // Every other algorithm simply never calls this.
            FrameConsumer finishingConsumer = (liveState, stepNumber) -> {
                lastStepWasFinishing = true;
                liveBoxCount = liveState.boxCount();
                if (history.shouldKeep(liveState, stepNumber)) {
                    history.record(new Frame(liveState.snapshot(), stepNumber, false, true));
                }
            };
            PackingSolution result = choice.run(rectangles, boxLength, seed, consumer, finishingConsumer,
                    TerminationCriterion.maxDuration(LOCAL_SEARCH_BUDGET));
            history.recordFinal(new Frame(result.snapshot(), -1, true, lastStepWasFinishing));
            finished = true;
        }, "algorithm-runner");
        worker.start();
    }

    /** Safe to call from the EDT at any time, including mid-run - see {@link FrameHistory#snapshot}. */
    List<Frame> frames() {
        return history.snapshot();
    }

    boolean isFinished() {
        return finished;
    }

    /** Live box count as of the most recent step the worker thread has reached - may be ahead of
     *  anything in {@link #frames()}, which only holds what {@link FrameHistory} chose to retain. */
    int liveBoxCount() {
        return liveBoxCount;
    }

    long liveStepNumber() {
        return liveStepNumber;
    }
}
