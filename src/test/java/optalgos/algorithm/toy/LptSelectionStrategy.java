package optalgos.algorithm.toy;

import optalgos.algorithm.greedy.SelectionStrategy;

import java.util.Collections;
import java.util.List;

/** Longest-processing-time-first: always place the largest remaining job next. A standard,
 *  well-known greedy heuristic for makespan minimization - good enough to prove genericity, no need
 *  for anything toy-problem-specific to be clever here. */
public final class LptSelectionStrategy implements SelectionStrategy<Integer, MachineAssignment> {

    @Override
    public Integer selectNext(MachineAssignment partial, List<Integer> remaining) {
        return Collections.max(remaining);
    }
}
