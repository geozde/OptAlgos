package optalgos.algorithm.toy;

import optalgos.algorithm.localsearch.Move;
import optalgos.algorithm.localsearch.Neighborhood;

import java.util.ArrayList;
import java.util.List;

/** Move-one-job neighborhood for the toy 2-machine scheduling problem: each candidate move relocates
 *  one job from its current machine to the other. Deliberately bad start: everything on machine A. */
public final class TwoMachineSwapNeighborhood implements Neighborhood<MachineAssignment> {

    private final List<Integer> jobs;

    public TwoMachineSwapNeighborhood(List<Integer> jobs) {
        this.jobs = List.copyOf(jobs);
    }

    @Override
    public MachineAssignment initialSolution() {
        MachineAssignment bad = MachineAssignment.empty();
        for (int job : jobs) {
            bad.assignToA(job);
        }
        return bad;
    }

    @Override
    public List<? extends Move<MachineAssignment>> candidateMoves(MachineAssignment current) {
        List<Move<MachineAssignment>> moves = new ArrayList<>();
        int makespanBefore = current.makespan();
        for (int i = 0; i < current.jobsOnA().size(); i++) {
            moves.add(relocationMove(current, true, i, makespanBefore));
        }
        for (int i = 0; i < current.jobsOnB().size(); i++) {
            moves.add(relocationMove(current, false, i, makespanBefore));
        }
        return moves;
    }

    private Move<MachineAssignment> relocationMove(MachineAssignment current, boolean fromAtoB, int index, int makespanBefore) {
        MachineAssignment result = current.withMoved(fromAtoB, index);
        double delta = result.makespan() - makespanBefore;
        return new Move<>() {
            @Override
            public MachineAssignment apply(MachineAssignment ignored) {
                return result;
            }

            @Override
            public double scoreDelta() {
                return delta;
            }
        };
    }
}
