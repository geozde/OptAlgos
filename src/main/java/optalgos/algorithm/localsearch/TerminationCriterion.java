package optalgos.algorithm.localsearch;

import java.time.Duration;

/** When a LocalSearch run should sfirtop. Can use a time budget or a 
 * stagnation limit, whichever comes first. */
@FunctionalInterface // can implement it with a lambda
public interface TerminationCriterion {

    boolean shouldStop(SearchProgress progress);

    // time budget
    static TerminationCriterion maxDuration(Duration duration) {
        long nanos = duration.toNanos();
        return progress -> progress.elapsedNanos() >= nanos;
    }

    // hard limit on number of iterations
    static TerminationCriterion maxIterations(long maxIterations) {
        return progress -> progress.iteration() >= maxIterations;
    }

    // to cut off a run early when it's not useful
    static TerminationCriterion maxIterationsWithoutImprovement(long maxStagnantIterations) {
        return progress -> progress.iterationsSinceImprovement() >= maxStagnantIterations;
    }

    // new criterion that stops if either this or other says stop
    default TerminationCriterion or(TerminationCriterion other) {
        return progress -> this.shouldStop(progress) || other.shouldStop(progress);
    }
}
