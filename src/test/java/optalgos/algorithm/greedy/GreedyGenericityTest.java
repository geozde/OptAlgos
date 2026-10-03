package optalgos.algorithm.greedy;

import optalgos.algorithm.toy.LptSelectionStrategy;
import optalgos.algorithm.toy.MachineAssignment;
import optalgos.algorithm.toy.TwoMachineScheduling;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves {@link Greedy} is genuinely generic the same way {@code LocalSearchGenericityTest} proves
 *  it for {@link optalgos.algorithm.localsearch.LocalSearch}: the exact same class, run against the
 *  rectangle-free toy scheduling problem. */
class GreedyGenericityTest {

    @Test
    void sameAlgorithmClassSolvesAnUnrelatedToyProblem() {
        List<Integer> jobs = List.of(5, 3, 8, 2, 9, 1, 7, 4);
        int totalDuration = jobs.stream().mapToInt(Integer::intValue).sum();

        var greedy = new Greedy<Integer, MachineAssignment>(new LptSelectionStrategy(), new TwoMachineScheduling());
        MachineAssignment result = greedy.run(MachineAssignment.empty(), jobs, GreedyListener.noOp());

        assertThat(result.jobsOnA().size() + result.jobsOnB().size()).isEqualTo(jobs.size());
        assertThat(result.loadA() + result.loadB()).isEqualTo(totalDuration);
        assertThat(result.makespan()).isLessThan(totalDuration);   // strictly better than dumping everything on one machine
    }

    @Test
    void listenerSeesEveryStepInOrder() {
        List<Integer> jobs = List.of(5, 3, 8, 2);
        var greedy = new Greedy<Integer, MachineAssignment>(new LptSelectionStrategy(), new TwoMachineScheduling());
        List<Integer> stepsSeen = new ArrayList<>();

        greedy.run(MachineAssignment.empty(), jobs, new GreedyListener<MachineAssignment>() {
            @Override
            public void onStepCompleted(MachineAssignment partial, int step, int totalSteps) {
                stepsSeen.add(step);
                assertThat(totalSteps).isEqualTo(jobs.size());
            }
        });

        assertThat(stepsSeen).containsExactly(1, 2, 3, 4);
    }
}
