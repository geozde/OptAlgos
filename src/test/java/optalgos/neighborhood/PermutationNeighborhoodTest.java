package optalgos.neighborhood;

import optalgos.algorithm.localsearch.LocalSearch;
import optalgos.algorithm.localsearch.SelectionPolicy;
import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.RectpackAlgorithms;
import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PermutationSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermutationNeighborhoodTest {

    @Test
    void initialSolutionPlacesEveryRectangleWithoutOverlap() {
        List<Rectangle> rectangles = new InstanceGenerator(1L).generate(new InstanceConfig(30, 3, 10, 3, 10, 20));
        var neighborhood = new PermutationNeighborhood(rectangles, 20, new SkylinePlacement(), new Random(1L));

        PermutationSolution start = neighborhood.initialSolution();

        assertThat(start.placement().allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(start.placement().hasAnyOverlap()).isFalse();
    }

    @Test
    void localSearchImprovesOnTheDeliberatelyBadStart() {
        // initialSolution() starts from the exact inverse of the decreasing-area order (see its
        // Javadoc), which is reliably worse than that reference at every shape with room to be
        // worse - so startBoxCount below has real headroom above what the search's own moves can
        // find, unlike a uniform random shuffle, which ties decreasing-area outright on roughly half
        // of these seeds and would leave nothing to improve.
        List<Rectangle> rectangles = new InstanceGenerator(4L).generate(new InstanceConfig(200, 4, 15, 4, 15, 25));
        var neighborhood = new PermutationNeighborhood(rectangles, 25, new SkylinePlacement(), new Random(4L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        int startBoxCount = neighborhood.initialSolution().placement().boxCount();
        PermutationSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(8)), (s, i) -> {
        });

        assertThat(result.placement().boxCount()).isLessThan(startBoxCount);
        assertThat(result.placement().allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(result.placement().hasAnyOverlap()).isFalse();
        assertThat(result.order().size()).isEqualTo(rectangles.size());
    }

    @Test
    void initialSolutionIsAlwaysWorseThanTheDecreasingAreaReference() {
        // The spec's "decidedly bad starting solution" requirement, pinned: never tied with, let
        // alone better than, the same decreasing-area order Greedy uses - on the specific instance
        // shape the GUI itself generates by default, where a uniform random shuffle (an earlier
        // version of this method) used to tie outright on roughly half of these seeds.
        int[] seeds = {1, 2, 3, 4, 5, 6, 7, 8};
        for (int seed : seeds) {
            List<Rectangle> rectangles = new InstanceGenerator(seed).generate(new InstanceConfig(50, 3, 15, 3, 15, 30));
            var neighborhood = new PermutationNeighborhood(rectangles, 30, new SkylinePlacement(), new Random(seed));

            int decreasingAreaBoxCount = RectpackAlgorithms.decreasingAreaGreedy()
                    .run(PackingSolution.empty(30), rectangles, (partial, step, total) -> { })
                    .boxCount();
            int initialBoxCount = neighborhood.initialSolution().placement().boxCount();

            assertThat(initialBoxCount)
                    .as("seed %d: initial solution must be strictly worse than the decreasing-area reference", seed)
                    .isGreaterThan(decreasingAreaBoxCount);
        }
    }

    @Test
    void everyRectangleStaysInThePermutationAcrossMoves() {
        // Swaps and moves must never lose or duplicate a rectangle.
        List<Rectangle> rectangles = new InstanceGenerator(9L).generate(new InstanceConfig(25, 3, 10, 3, 10, 20));
        var neighborhood = new PermutationNeighborhood(rectangles, 20, new SkylinePlacement(), new Random(9L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        PermutationSolution result = search.run(TerminationCriterion.maxIterations(200), (s, i) -> {
        });

        assertThat(result.order().order()).hasSize(rectangles.size());
        assertThat(result.order().order()).containsExactlyInAnyOrderElementsOf(rectangles);
    }

    @Test
    void everyAcceptedStateIsExactlyWhatReplacingItsOwnPermutationProduces() {
        // The invariant the incremental re-placement inside the neighborhood must uphold, and the one
        // whose violation silently disabled this whole neighborhood: scoring a candidate re-places
        // only the changed suffix, which is only sound if the result is indistinguishable from
        // placing that whole permutation from scratch. It was not - dropping the suffix left empty
        // box slots behind, which the placement rule prefers over any partly-filled box and refills
        // at once, so a move that genuinely closed a box scored as though it had closed nothing and
        // the search accepted no move at all. Asserted on every accepted state, not just the final
        // one, since a single divergence anywhere makes every later score meaningless.
        List<Rectangle> rectangles = new InstanceGenerator(11L).generate(new InstanceConfig(120, 3, 15, 3, 15, 30));
        SkylinePlacement placementRule = new SkylinePlacement();
        var neighborhood = new PermutationNeighborhood(rectangles, 30, placementRule, new Random(11L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        List<String> divergences = new ArrayList<>();
        PermutationSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(3)),
                (state, iteration) -> {
                    PackingSolution fromScratch =
                            placementRule.placeAll(PackingSolution.empty(30), state.order().order());
                    if (!placementFingerprint(state.placement()).equals(placementFingerprint(fromScratch))) {
                        divergences.add("iteration " + iteration + ": " + state.placement().boxCount()
                                + " boxes, but re-placing its own permutation gives " + fromScratch.boxCount());
                    }
                });

        assertThat(divergences).isEmpty();
        assertThat(result.placement().boxCount())
                .isEqualTo(placementRule.placeAll(PackingSolution.empty(30), result.order().order()).boxCount());
    }

    @Test
    void searchProgressesInManyDistinctStepsRatherThanOneJump() {
        // A run has to be watchable, not just correct: the GUI visualizes the placement between
        // iterations, so a search that lands on a good solution in one or two accepted moves has
        // nothing to show. Distinct states are counted, not iterations - a plateau retry hands back
        // the SAME state and must not count towards this.
        List<Rectangle> rectangles = new InstanceGenerator(12L).generate(new InstanceConfig(50, 3, 15, 3, 15, 30));
        var neighborhood = new PermutationNeighborhood(rectangles, 30, new SkylinePlacement(), new Random(12L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        Set<PermutationSolution> distinctStates = Collections.newSetFromMap(new IdentityHashMap<>());
        PermutationSolution start = neighborhood.initialSolution();
        PermutationSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(8)),
                (state, iteration) -> distinctStates.add(state));

        assertThat(distinctStates).as("distinct placements the run passes through").hasSizeGreaterThanOrEqualTo(10);
        assertThat(result.placement().boxCount()).isLessThan(start.placement().boxCount());
    }

    private static Map<Integer, String> placementFingerprint(PackingSolution placement) {
        Map<Integer, String> byRectangleId = new HashMap<>();
        for (PlacedRectangle placed : placement.allPlacedRectangles()) {
            byRectangleId.put(placed.rectangle().id(),
                    placed.boxIndex() + ":" + placed.x() + "," + placed.y() + "," + placed.orientation());
        }
        return byRectangleId;
    }
}
