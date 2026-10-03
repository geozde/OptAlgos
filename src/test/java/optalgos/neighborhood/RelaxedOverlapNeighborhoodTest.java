package optalgos.neighborhood;

import optalgos.algorithm.localsearch.LocalSearch;
import optalgos.algorithm.localsearch.LocalSearchListener;
import optalgos.algorithm.localsearch.SelectionPolicy;
import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RelaxedOverlapNeighborhoodTest {

    @Test
    void initialSolutionIsCompactAndOverlapping() {
        // All rectangles stacked into a round-robin group of boxes sized to the area lower bound:
        // deliberately infeasible rather than deliberately spread out, which is what the spec's "der
        // Prozentsatz ist zu Beginn 100, so dass eine Optimallösung einfach zu finden ist" describes.
        List<Rectangle> rectangles = new InstanceGenerator(1L).generate(new InstanceConfig(20, 3, 10, 3, 10, 20));
        var neighborhood = new RelaxedOverlapNeighborhood(rectangles, 20, new SkylinePlacement(), new Random(1L));

        PackingSolution start = neighborhood.initialSolution();

        assertThat(start.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(start.hasAnyOverlap()).isTrue();
        assertThat(start.boxCount()).isLessThan(rectangles.size());
    }

    @RepeatedTest(10)
    void finalResultIsAlwaysGuaranteedOverlapFree() {
        // The core spec requirement: whatever happens mid-search - annealing converging cleanly, or
        // the final repair pass firing because it didn't - the result handed back must have exactly
        // zero overlap, every time.
        long seed = ThreadLocalRandom.current().nextLong();
        List<Rectangle> rectangles = new InstanceGenerator(seed).generate(new InstanceConfig(50, 3, 12, 3, 12, 25));
        var neighborhood = new RelaxedOverlapNeighborhood(rectangles, 25, new SkylinePlacement(), new Random(seed));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(8)), (s, i) -> {
        });

        assertThat(result.hasAnyOverlap()).as("seed=%d", seed).isFalse();
        assertThat(result.allPlacedRectangles()).hasSize(rectangles.size());
    }

    @Test
    void theSearchItselfResolvesTheOverlapRatherThanLeavingItToTheRepairPass() {
        // Regression test for this class's single biggest historical defect. The search used to be
        // unable to open a box at all, so an arrangement started at the area lower bound could never
        // spread far enough to become feasible, and the repair pass - a plain greedy re-placement -
        // silently did the bulk of the work: instrumented at 594 of 1000 rectangles re-placed at
        // n=1000, 286/500, 97/200. Every rectangle repair touches is reported as a finishing step, so
        // counting those is a direct measure of how much of the answer the SEARCH actually produced.
        List<Rectangle> rectangles = new InstanceGenerator(11L).generate(new InstanceConfig(200, 3, 20, 3, 20, 50));
        var neighborhood = new RelaxedOverlapNeighborhood(rectangles, 50, new SkylinePlacement(), new Random(11L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
        AtomicInteger finishingSteps = new AtomicInteger();

        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(8)),
                new LocalSearchListener<>() {
                    @Override
                    public void onAccepted(PackingSolution state, long iteration) {
                    }

                    @Override
                    public void onFinishingStep(PackingSolution state) {
                        finishingSteps.incrementAndGet();
                    }
                });

        assertThat(result.hasAnyOverlap()).isFalse();
        // Generous by design - the point is the order of magnitude, not a tuned threshold. Anything
        // near the ~50% the broken version needed would fail this outright.
        assertThat(finishingSteps.get()).isLessThan(rectangles.size() / 4);
    }

    @Test
    void theSearchActuallyVisitsOverlappingStates() {
        // What makes this a DIFFERENT neighborhood rather than a slower copy of the geometry-based
        // one. A previous version was found - only by asking this question directly - to produce no
        // overlapping state at all across a full 126,718-iteration run: its annealing schedule and
        // overlap-tolerant moves were dead code, and its results were correspondingly indistinct.
        List<Rectangle> rectangles = new InstanceGenerator(12L).generate(new InstanceConfig(120, 3, 15, 3, 15, 40));
        var neighborhood = new RelaxedOverlapNeighborhood(rectangles, 40, new SkylinePlacement(), new Random(12L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
        AtomicBoolean sawOverlap = new AtomicBoolean();

        search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(3)), (state, iteration) -> {
            if (state.hasAnyOverlap()) {
                sawOverlap.set(true);
            }
        });

        assertThat(sawOverlap).isTrue();
    }

    @Test
    void localSearchResolvesTheOverlapWithoutLosingMuchOfTheStartsCompactness() {
        // "Improvement" isn't "final box count < starting box count" here - the start is deliberately
        // infeasible and sits AT the area lower bound, not a real packing, so there is nothing to
        // compare against on that axis. What matters: the search resolves the overlap (a hard
        // requirement) and the feasible result stays close to the area lower bound, i.e. it kept the
        // start's compactness while fixing what made it invalid. The bound below is what the working
        // implementation clears with room to spare (measured 1.05-1.10x across n=50..1000); it was
        // 2x when this class could not beat Greedy on a single instance.
        List<Rectangle> rectangles = new InstanceGenerator(6L).generate(new InstanceConfig(200, 3, 20, 3, 20, 50));
        var neighborhood = new RelaxedOverlapNeighborhood(rectangles, 50, new SkylinePlacement(), new Random(6L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        long totalArea = rectangles.stream().mapToLong(Rectangle::area).sum();
        long areaLowerBound = (totalArea + 50L * 50 - 1) / (50L * 50);
        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(8)), (s, i) -> {
        });

        assertThat(result.hasAnyOverlap()).isFalse();
        assertThat(result.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(result.boxCount()).isLessThanOrEqualTo((int) Math.ceil(areaLowerBound * 1.35));
    }
}
