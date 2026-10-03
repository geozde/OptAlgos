package optalgos.neighborhood;

import optalgos.algorithm.localsearch.LocalSearch;
import optalgos.algorithm.localsearch.SelectionPolicy;
import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeometricNeighborhoodTest {

    @Test
    void initialSolutionCommitsEachBoxToAtLeastThreeRectanglesOrHalfFullBeforeMovingOn() {
        // No longer one-rectangle-per-box (see initialSolution's Javadoc for why) - several
        // rectangles per box, placed via the same SkylinePlacement search the neighborhood's own
        // moves use, but each box committed (never revisited) once it clears a modest threshold -
        // deliberately worse than a full best-fit construction, but nowhere near as extreme as
        // one-per-box.
        List<Rectangle> rectangles = new InstanceGenerator(1L).generate(new InstanceConfig(20, 2, 10, 2, 10, 20));
        var neighborhood = new GeometricNeighborhood(rectangles, 20, new Random(1L));

        PackingSolution start = neighborhood.initialSolution();

        assertThat(start.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(start.hasAnyOverlap()).isFalse();
        assertThat(start.boxCount()).isLessThan(rectangles.size());
    }

    @Test
    void localSearchConvincinglyImprovesOnTheBadStart() {
        // Genuine improvement, not just "less than or equal" (a weaker bound here previously let a
        // real bug - see relocationDelta's Javadoc - pass silently, since 200->199 still satisfies
        // "less than or equal"), AND a real quality bar independent of exactly how bad the starting
        // construction happens to be: within a small, fixed multiple of the area lower bound (the
        // fewest boxes even theoretically possible, ignoring all packing waste) - "half of whatever
        // the start happened to be" doesn't hold as a bound once the start itself isn't the most
        // extreme construction possible (see GeometricNeighborhood#initialSolution's Javadoc).
        List<Rectangle> rectangles = new InstanceGenerator(7L).generate(new InstanceConfig(40, 3, 8, 3, 8, 20));
        var neighborhood = new GeometricNeighborhood(rectangles, 20, new Random(7L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        int badStartBoxCount = neighborhood.initialSolution().boxCount();
        long totalArea = rectangles.stream().mapToLong(Rectangle::area).sum();
        long areaLowerBound = (totalArea + 20L * 20 - 1) / (20L * 20);
        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(5)), (s, i) -> {
        });

        assertThat(result.boxCount()).isLessThan(badStartBoxCount);
        assertThat(result.boxCount()).isLessThanOrEqualTo((int) areaLowerBound * 2);
        assertThat(result.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(result.hasAnyOverlap()).isFalse();
    }

    @Test
    void reachesTheKnownOptimumOnAnExactlySolvableInstance() {
        // Six 5x5 rectangles, L=10: exactly 4 fit per box (a 2x2 arrangement, edges touching), so
        // the true optimum is ceil(6/4) = 2 boxes. A search that gets stuck reshuffling which box
        // is empty (the bug relocationDelta's Javadoc describes) would stall well above this.
        List<Rectangle> rectangles = List.of(
                new Rectangle(1, 5, 5), new Rectangle(2, 5, 5), new Rectangle(3, 5, 5),
                new Rectangle(4, 5, 5), new Rectangle(5, 5, 5), new Rectangle(6, 5, 5));
        var neighborhood = new GeometricNeighborhood(rectangles, 10, new Random(2L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(5)), (s, i) -> {
        });

        assertThat(result.boxCount()).isEqualTo(2);
        assertThat(result.hasAnyOverlap()).isFalse();
    }

    @Test
    void firstImprovementAlsoConverges() {
        List<Rectangle> rectangles = new InstanceGenerator(11L).generate(new InstanceConfig(40, 3, 8, 3, 8, 20));
        var neighborhood = new GeometricNeighborhood(rectangles, 20, new Random(11L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.FIRST_IMPROVEMENT);

        int badStartBoxCount = neighborhood.initialSolution().boxCount();
        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(5)), (s, i) -> {
        });

        assertThat(result.boxCount()).isLessThanOrEqualTo(badStartBoxCount);
        assertThat(result.hasAnyOverlap()).isFalse();
    }

    @Test
    void candidateMovesAreBoundedNotExhaustiveOnALargeInstance() {
        List<Rectangle> rectangles = new InstanceGenerator(3L).generate(new InstanceConfig(500, 3, 15, 3, 15, 50));
        var neighborhood = new GeometricNeighborhood(rectangles, 50, new Random(3L));
        PackingSolution start = neighborhood.initialSolution();   // 500 boxes, 1 rectangle each

        int moveCount = neighborhood.candidateMoves(start).size();

        // An exhaustive search (500 rectangles x 500 candidate boxes) would consider ~250,000
        // positions; this is bounded to roughly (sources x destinations), a couple orders of
        // magnitude fewer.
        assertThat(moveCount).isLessThan(1500);
    }

    @Test
    void everyAcceptedStateIsACompleteOverlapFreePacking() {
        // The invariant the compound moves must uphold. Draining a box and re-nesting a destination
        // around an arriving rectangle (see RepackMove) both remove SEVERAL placements and put back
        // several others, against a PackingSolution that deliberately validates neither overlap nor
        // completeness on place() - so a rectangle dropped on the way through, or two put back in the
        // same space, would be silently accepted and only surface as a wrong picture in the GUI.
        // Checked on every accepted state rather than only the final one, since the GUI renders these.
        List<Rectangle> rectangles = new InstanceGenerator(21L).generate(new InstanceConfig(150, 3, 15, 3, 15, 30));
        var neighborhood = new GeometricNeighborhood(rectangles, 30, new Random(21L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        List<String> problems = new ArrayList<>();
        int[] statesChecked = {0};
        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(5)),
                (state, iteration) -> {
                    statesChecked[0]++;
                    if (problems.size() >= 3) {
                        return;
                    }
                    List<PlacedRectangle> placed = state.allPlacedRectangles();
                    Set<Integer> ids = new HashSet<>();
                    for (PlacedRectangle p : placed) {
                        ids.add(p.rectangle().id());
                    }
                    if (placed.size() != rectangles.size() || ids.size() != rectangles.size()) {
                        problems.add("iteration " + iteration + " holds " + placed.size() + " placements ("
                                + ids.size() + " distinct) of " + rectangles.size());
                    }
                    if (state.hasAnyOverlap()) {
                        problems.add("iteration " + iteration + " has overlapping rectangles");
                    }
                });

        assertThat(problems).isEmpty();
        assertThat(statesChecked[0]).isGreaterThan(10);
        assertThat(result.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(result.hasAnyOverlap()).isFalse();
    }

    @Test
    void theRunNeverReturnsWorseThanTheBestPackingItPassedThrough() {
        // GeometricNeighborhood#perturb is deliberately allowed to make a state worse - that is the
        // only way out of a genuine local optimum - and LocalSearch itself keeps no incumbent, it
        // returns whatever state it stops on. finish() is what closes that gap, and this is its
        // contract: a run interrupted mid-perturbation must still hand back the best packing it held.
        List<Rectangle> rectangles = new InstanceGenerator(22L).generate(new InstanceConfig(200, 3, 15, 3, 15, 30));
        var neighborhood = new GeometricNeighborhood(rectangles, 30, new Random(22L));
        var search = new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);

        int[] bestPassedThrough = {Integer.MAX_VALUE};
        PackingSolution result = search.run(TerminationCriterion.maxDuration(Duration.ofSeconds(5)),
                (state, iteration) -> bestPassedThrough[0] = Math.min(bestPassedThrough[0], state.boxCount()));

        assertThat(result.boxCount()).isLessThanOrEqualTo(bestPassedThrough[0]);
        assertThat(result.hasAnyOverlap()).isFalse();
    }

    @Test
    void aBadlyNestedBoxOffersAnImprovingMoveThatStaysInsideThatBox() {
        // The spec requires this neighborhood to move rectangles "sowohl innerhalb einer Box als auch
        // von einer Box zur anderen", and the within-box half used to be unreachable: the objective
        // scored every such move at exactly 0.0 and best-improvement selection only ever takes a
        // strictly negative delta, so the moves were generated on every iteration and never once
        // chosen. Here rectangle 2 floats with a gap beneath it; re-seating it inside its own box
        // lowers that box's skyline, which the compaction term now registers as an improvement.
        List<Rectangle> rectangles = List.of(new Rectangle(1, 5, 5), new Rectangle(2, 5, 5));
        var neighborhood = new GeometricNeighborhood(rectangles, 20, new Random(1L));
        PackingSolution state = PackingSolution.empty(20);
        state.place(new PlacedRectangle(rectangles.get(0), 0, 0, 0, Orientation.NORMAL));
        state.place(new PlacedRectangle(rectangles.get(1), 0, 0, 10, Orientation.NORMAL));

        boolean improvingWithinBox = neighborhood.candidateMoves(state).stream()
                .anyMatch(move -> move.scoreDelta() < 0
                        && move instanceof RelocationMove relocation
                        && relocation.from().boxIndex() == relocation.to().boxIndex());

        assertThat(improvingWithinBox).isTrue();
    }
}
