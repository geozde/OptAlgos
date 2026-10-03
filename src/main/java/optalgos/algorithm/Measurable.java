package optalgos.algorithm;

/**
 * Real, problem-level score. Lower is better.
 * 
 * Implemented by solution types, e.g. number of boxes for rectProblem. 
 * 
 * Never referenced by localsearch or greedy themselves. They only compare a neighborhood's or
 * strategy's private "search objective" (via Move.scoreDelta). 
 */
public interface Measurable {

    /** Lower is better. */
    double trueObjective();
}
