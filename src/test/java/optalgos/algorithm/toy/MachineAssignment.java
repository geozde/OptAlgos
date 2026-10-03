package optalgos.algorithm.toy;

import java.util.ArrayList;
import java.util.List;

/**
 * A (possibly partial) assignment of jobs to 2 machines - the toy problem's solution representation,
 * used only to prove {@link optalgos.algorithm.localsearch.LocalSearch} and
 * {@link optalgos.algorithm.greedy.Greedy} are genuinely generic (see the project plan). Deliberately
 * has nothing to do with rectangle packing.
 */
public final class MachineAssignment {

    private final List<Integer> machineA = new ArrayList<>();
    private final List<Integer> machineB = new ArrayList<>();

    public static MachineAssignment empty() {
        return new MachineAssignment();
    }

    public MachineAssignment copy() {
        MachineAssignment copy = new MachineAssignment();
        copy.machineA.addAll(this.machineA);
        copy.machineB.addAll(this.machineB);
        return copy;
    }

    public void assignToA(int job) {
        machineA.add(job);
    }

    public void assignToB(int job) {
        machineB.add(job);
    }

    public List<Integer> jobsOnA() {
        return List.copyOf(machineA);
    }

    public List<Integer> jobsOnB() {
        return List.copyOf(machineB);
    }

    public int loadA() {
        return machineA.stream().mapToInt(Integer::intValue).sum();
    }

    public int loadB() {
        return machineB.stream().mapToInt(Integer::intValue).sum();
    }

    public int makespan() {
        return Math.max(loadA(), loadB());
    }

    /** Moves the job at this index of one machine's list to the other machine. */
    public MachineAssignment withMoved(boolean fromAtoB, int index) {
        MachineAssignment next = copy();
        if (fromAtoB) {
            int job = next.machineA.remove(index);
            next.machineB.add(job);
        } else {
            int job = next.machineB.remove(index);
            next.machineA.add(job);
        }
        return next;
    }
}
