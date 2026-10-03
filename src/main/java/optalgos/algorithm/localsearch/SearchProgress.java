package optalgos.algorithm.localsearch;

// record is just data
// compiler generates the constructor, getters

/** Snapshot of how far a LocalSearch run is, passed to TerminationCriterion. 
 * Can decouple termination criteria (time, iterations...) from local search to keep
 * everything generic
 * 
 * Packages up the raw facts about how the LocalSearch run is going into this one small 
 * object, and hands it to TerminationCriterion.
 * 
*/
public record SearchProgress(long elapsedNanos, long iteration, long iterationsSinceImprovement) {
}
