package optalgos.algorithm.localsearch;

import java.util.List;
import java.util.Optional;

/**
 * Generic local search: 
 * in every step move to a better neighbor until stuck;
 * when stuck, try asking the neighborhood whether it has anything left to try before really stopping. 
 * 
 * Can stop because it reached a plateau or we hit external constraints (e.g. timing).
 * In both cases, we call finish exactly once before return, so a neighborhood can guarantee some 
 * property of its result unconditionally
 * 
 * Contains no knowledge whatsoever of what S represents, what a move means, or what problem is being 
 * solved.
 *
 * Note: class must never import anything outside optalgos.algorithm
 * 
 * @param <S> solution representation being searched over.
 */
public final class LocalSearch<S> {

    private final Neighborhood<S> neighborhood;
    private final SelectionPolicy policy;

    public LocalSearch(Neighborhood<S> neighborhood, SelectionPolicy policy) {
        this.neighborhood = neighborhood;
        this.policy = policy;
    }

    public S run(TerminationCriterion termination, LocalSearchListener<S> listener) {
        long startNanos = System.nanoTime();
        // starting neighborhood
        S current = neighborhood.initialSolution();
        // report the start solution too, so observers (e.g. the GUI) can show where the search began
        listener.onAccepted(current, 0);
        long iteration = 0;
        long lastImprovementIteration = 0;

        // keep looping
        while (true) {
            // identify possible moves
            List<? extends Move<S>> moves = neighborhood.candidateMoves(current);
            // then possible choose one
            // chosen might be None! 
            Optional<? extends Move<S>> chosen = policy.select(moves);

            // chose a move
            if (chosen.isPresent()) {
                current = chosen.get().apply(current);
                iteration++;
                // track that we improved in this iteration
                lastImprovementIteration = iteration;
                listener.onAccepted(current, iteration);
            } 
            // did not chose a move => plateau 
            else {
                // try neighborhood tricks to get out
                // listener::onFinishingStep is a method reference -> neighborhood methods can track progress
                Optional<S> continuation = neighborhood.onPlateau(current, listener::onFinishingStep);
                // NATURAL STOP
                // if no tricks, break the loop
                if (continuation.isEmpty()) {
                    break;
                }
                current = continuation.get();
                iteration++;
                listener.onAccepted(current, iteration);
            }

            // FORCED STOP
            // check external stopping conditions, e.g. time limit or too many iterations without improvement
            SearchProgress progress = new SearchProgress(
                    System.nanoTime() - startNanos, iteration, iteration - lastImprovementIteration);
            if (termination.shouldStop(progress)) {
                break;
            }
        }

        // when stopped, return current solution
        // call finish to check / fix final result
        current = neighborhood.finish(current, listener::onFinishingStep); 
        listener.onFinished(current, iteration);
        return current;
    }
}
