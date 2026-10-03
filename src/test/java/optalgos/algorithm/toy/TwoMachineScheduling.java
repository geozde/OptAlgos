package optalgos.algorithm.toy;

import optalgos.algorithm.greedy.SolutionBuilder;

/** How a single job gets folded into a partial machine assignment for the toy greedy run: always
 *  onto whichever machine currently has less load. */
public final class TwoMachineScheduling implements SolutionBuilder<Integer, MachineAssignment> {

    @Override
    public MachineAssignment extend(MachineAssignment partial, Integer job) {
        MachineAssignment next = partial.copy();
        if (next.loadA() <= next.loadB()) {
            next.assignToA(job);
        } else {
            next.assignToB(job);
        }
        return next;
    }
}
