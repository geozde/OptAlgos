package optalgos.rectpack;

import optalgos.algorithm.greedy.Greedy;
import optalgos.algorithm.localsearch.LocalSearch;
import optalgos.algorithm.localsearch.SelectionPolicy;
import optalgos.greedystrategy.BestFitAdaptiveStrategy;
import optalgos.greedystrategy.DecreasingAreaStrategy;
import optalgos.neighborhood.GeometricNeighborhood;
import optalgos.neighborhood.PermutationNeighborhood;
import optalgos.neighborhood.RelaxedOverlapNeighborhood;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PermutationSolution;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;

import java.util.List;
import java.util.Random;

/**
 * Wires the generic engines to the rectangle-packing problem in one place, so the GUI and benchmark
 * harness never duplicate this wiring - and so a reader has one place to see every algorithm x
 * neighborhood/strategy combination that exists. Nothing here is used by {@code optalgos.algorithm}
 * itself; this is purely a convenience layer above it.
 */
public final class RectpackAlgorithms {

    private RectpackAlgorithms() {
    }

    public static Greedy<Rectangle, PackingSolution> decreasingAreaGreedy() {
        return new Greedy<>(new DecreasingAreaStrategy(), new SkylinePlacement());
    }

    public static Greedy<Rectangle, PackingSolution> bestFitAdaptiveGreedy() {
        return bestFitAdaptiveGreedy(new Random());
    }

    public static Greedy<Rectangle, PackingSolution> bestFitAdaptiveGreedy(Random random) {
        SkylinePlacement placement = new SkylinePlacement();
        return new Greedy<>(new BestFitAdaptiveStrategy(placement, random), placement);
    }

    public static LocalSearch<PackingSolution> geometricLocalSearch(List<Rectangle> rectangles, int boxLength) {
        return geometricLocalSearch(rectangles, boxLength, new Random());
    }

    public static LocalSearch<PackingSolution> geometricLocalSearch(List<Rectangle> rectangles, int boxLength, Random random) {
        GeometricNeighborhood neighborhood = new GeometricNeighborhood(rectangles, boxLength, random);
        return new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
    }

    public static LocalSearch<PermutationSolution> permutationLocalSearch(List<Rectangle> rectangles, int boxLength) {
        return permutationLocalSearch(rectangles, boxLength, new Random());
    }

    public static LocalSearch<PermutationSolution> permutationLocalSearch(List<Rectangle> rectangles, int boxLength, Random random) {
        PermutationNeighborhood neighborhood = new PermutationNeighborhood(rectangles, boxLength, new SkylinePlacement(), random);
        return new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
    }

    public static LocalSearch<PackingSolution> relaxedOverlapLocalSearch(List<Rectangle> rectangles, int boxLength) {
        return relaxedOverlapLocalSearch(rectangles, boxLength, new Random());
    }

    public static LocalSearch<PackingSolution> relaxedOverlapLocalSearch(List<Rectangle> rectangles, int boxLength, Random random) {
        RelaxedOverlapNeighborhood neighborhood = new RelaxedOverlapNeighborhood(rectangles, boxLength, new SkylinePlacement(), random);
        return new LocalSearch<>(neighborhood, SelectionPolicy.BEST_IMPROVEMENT);
    }
}
