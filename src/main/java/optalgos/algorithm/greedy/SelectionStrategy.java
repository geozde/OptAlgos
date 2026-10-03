package optalgos.algorithm.greedy;

import java.util.List;

/**
 * Chooses the next candidate to add, given the current partial solution and what's left. 
 * Called by greedy in every iteration.
 * 
 *
 * @param <C> type of candidate items placed one at a time.
 * @param <S> solution representation (partial or complete)
 */
public interface SelectionStrategy<C, S> {

    // partial allows the list to adapt to the current solution
    // but can also be ignored and strategy can just operate on remaining candidates alone
    C selectNext(S partial, List<C> remaining);
}
