package optalgos.algorithm.localsearch;

import optalgos.algorithm.toy.MachineAssignment;
import optalgos.algorithm.toy.TwoMachineSwapNeighborhood;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link LocalSearch} is genuinely generic by running the exact same class against a toy
 * 2-machine scheduling problem that has nothing to do with rectangle packing. If this compiles and
 * passes, LocalSearch cannot possibly contain rectangle-specific logic - the single most important
 * artifact for the "entscheidend wichtig für die Abnahme" genericity requirement (see the project
 * plan). Complements, rather than replaces, {@code ArchitectureRulesTest}'s mechanical package check.
 */
class LocalSearchGenericityTest {

    @Test
    void sameAlgorithmClassSolvesAnUnrelatedToyProblem() {
        List<Integer> jobs = List.of(5, 3, 8, 2, 9, 1, 7, 4);
        var neighborhood = new TwoMachineSwapNeighborhood(jobs);
        int badStartMakespan = neighborhood.initialSolution().makespan();

        var search = new LocalSearch<MachineAssignment>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
        MachineAssignment result = search.run(TerminationCriterion.maxIterations(10_000), LocalSearchListener.noOp());

        assertThat(result.makespan()).isLessThan(badStartMakespan);
        assertThat(result.jobsOnA().size() + result.jobsOnB().size()).isEqualTo(jobs.size());
    }

    @Test
    void firstImprovementAlsoWorksUnchanged() {
        List<Integer> jobs = List.of(5, 3, 8, 2, 9, 1, 7, 4);
        var neighborhood = new TwoMachineSwapNeighborhood(jobs);
        int badStartMakespan = neighborhood.initialSolution().makespan();

        var search = new LocalSearch<MachineAssignment>(neighborhood, SelectionPolicy.FIRST_IMPROVEMENT);
        MachineAssignment result = search.run(TerminationCriterion.maxIterations(10_000), LocalSearchListener.noOp());

        assertThat(result.makespan()).isLessThanOrEqualTo(badStartMakespan);
    }

    @Test
    void stopsWhenTerminationCriterionFires() {
        List<Integer> jobs = List.of(5, 3, 8, 2, 9, 1, 7, 4);
        var neighborhood = new TwoMachineSwapNeighborhood(jobs);

        var search = new LocalSearch<MachineAssignment>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
        long[] lastIterationSeen = {0};
        search.run(TerminationCriterion.maxIterations(1),
                (state, iteration) -> lastIterationSeen[0] = iteration);

        assertThat(lastIterationSeen[0]).isLessThanOrEqualTo(1);
    }
}
