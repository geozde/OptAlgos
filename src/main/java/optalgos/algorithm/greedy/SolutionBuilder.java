package optalgos.algorithm.greedy;

/**
 * Generic class that decides how a chosen candidate gets inserted into partial solution. 
 * 
 * Independent of SelectionStrategy so that we can distinguish
 * - Which candidate to pick next? (SelectionStrategy)
 * - Where to insert a candidate? (SolutionBuilder)
 *
 * @param <C> type of candidate items placed one at a time.
 * @param <S> solution representation (partial or complete)
 */
public interface SolutionBuilder<C, S> {

    // takes a partial solution and adds candidate to it.
    // returns a new partial solution
    S extend(S partial, C candidate);
}
