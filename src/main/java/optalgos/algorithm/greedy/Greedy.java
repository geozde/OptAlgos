package optalgos.algorithm.greedy;

import java.util.ArrayList;
import java.util.List;

/**
 * Generic greedy construction: 
 * repeatedly ask {@code selection} which remaining candidate to place next
 * fold it in via {@code builder}, until nothing remains. 
 * 
 * Contains no knowledge of what a candidate or a solution represents
 *
 * Note: Class must never import anything outside optalgos.algorithm
 *
 * @param <C> type of candidate items being placed one at a time.
 * @param <S> solution representation (partial or complete)
 */
public final class Greedy<C, S> {

    private final SelectionStrategy<C, S> selection; // selects next candidate
    private final SolutionBuilder<C, S> builder; // decides what to do with candidate

    // constructor
    public Greedy(SelectionStrategy<C, S> selection, SolutionBuilder<C, S> builder) {
        this.selection = selection;
        this.builder = builder;
    }

    public S run(S empty, List<C> candidates, GreedyListener<S> listener) {
        // get list of remaining candidates
        List<C> remaining = new ArrayList<>(candidates);
        S current = empty;
        int total = remaining.size();
        int step = 0;

        // loop while there are still candidates
        while (!remaining.isEmpty()) {
            // get candidate with selectionstrategy
            C chosen = selection.selectNext(current, remaining);
            remaining.remove(chosen);
            // update current solution by adding candidate to it with Builder
            current = builder.extend(current, chosen);
            step++;
            // callback method: called whenever candidate is placed intosolution 
            listener.onStepCompleted(current, step, total);
        }

        // callback: hand over final, complete solution
        // notifies listener that algo is finished
        listener.onFinished(current);
        return current;
    }
}
