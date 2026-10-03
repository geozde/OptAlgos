package optalgos.algorithm.localsearch;

/**
 * One candidate step of a solution. 
 * scoreDelta gives a score to move in whatever units the 
 * Neighborhood uses internally. Used so that scores are 
 * hidden from LocalSearch, which only uses deltas to pick a move.
 * 
 * Need this class to rank candidates and choose one while staying generic
 *
 * @param <S> solution that move belongs to.
 */
public interface Move<S> {

    /**
     * Applies this move to parameter current (= current solution we're looking at). 
     * Implementations can modify and return the same reference, or return a fresh value
     */
    S apply(S current);

    /** what change would this move cause to objective?
     *  negative means improving, if we're minimising. */
    double scoreDelta();
}
