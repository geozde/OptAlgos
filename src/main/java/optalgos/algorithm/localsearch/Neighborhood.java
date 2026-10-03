package optalgos.algorithm.localsearch;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything a LocalSearch needs to know about a problem and a chosen neighborhood definition 
 * implementations live entirely outside of optalgos.algorithm
 * 
 * This is where problem-specific knowledge enters the system
 *
 * @param <S> solution representation this neighborhood searches over.
 */
public interface Neighborhood<S> {

    /** deliberately bad starting solution so we can see some improvement */
    S initialSolution();

    /** Given current state, determines list of possible moves to be considered next
     *  Can be a subset instead of the full neighborhood (efficiency reasons). */
    List<? extends Move<S>> candidateMoves(S current);

    /**
     * Called when no candidate move improves on current solution, i.e. we're at a plateau).
     * 
     * The default is just "we're really stuck, stop".
     * 
     * A neighborhood that wants to keep going, can override this. It can for example relax an 
     * internal constraint and continue from a modified state (e.g. relaxed-overlap neighborhood).
     * It then returns that state instead.
     * 
     * LocalSearch simply continues calling candidateMoves from there. 
     */
    default Optional<S> onPlateau(S current, Consumer<S> onIntermediateStep) {
        return Optional.empty();
    }

    /**
     * Called exactly once
     * 
     * It can be called because we naturally reached a plateau or because of external constraints.
     * 
     * Most neighborhoods have nothing to add here and inherit the default. 
     * 
     * This exists because a neighborhood's internal state machine might only guarantee some 
     * property (like feasibility) once it reaches its own notion of "done". If it stops because
     * the time budget expires first, that guarantee was never actually met.
     * 
     * finish is the last chance to make it still true.
     *
     * onIntermediateStep is a callback to report internally passed states while escaping plateau 
     */
    default S finish(S current, Consumer<S> onIntermediateStep) {
        return current;
    }
}
