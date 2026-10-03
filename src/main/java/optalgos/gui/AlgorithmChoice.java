package optalgos.gui;

import optalgos.algorithm.localsearch.LocalSearchListener;
import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.RectpackAlgorithms;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PermutationSolution;
import optalgos.rectpack.model.Rectangle;

import java.util.List;
import java.util.Random;

/**
 * Every algorithm x neighborhood/strategy combination the GUI can run, and how to run each one - the
 * GUI-facing counterpart to {@link optalgos.rectpack.RectpackAlgorithms}, which does the actual
 * wiring. Each constant streams progress through a {@link FrameConsumer} fed the LIVE (not yet
 * snapshotted) solution after every accepted search step, and a SECOND {@link FrameConsumer} fed any
 * intermediate states a neighborhood's {@code finish}/{@code onPlateau} hooks choose to report (see
 * {@link optalgos.algorithm.localsearch.Neighborhood#finish} - relaxed-overlap's from-scratch repair
 * rebuild is the one case among these five that actually uses it; every other constant's neighborhood
 * has nothing to report there, so its second consumer is simply never called). {@link AlgorithmRunner}
 * decides what to keep and when to snapshot, for both.
 */
public enum AlgorithmChoice {

    GREEDY_DECREASING_AREA("Greedy - Decreasing Area") {
        @Override
        PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                             FrameConsumer onFinishingStep, TerminationCriterion budget) {
            return RectpackAlgorithms.decreasingAreaGreedy().run(PackingSolution.empty(boxLength), rectangles,
                    (partial, step, total) -> onStep.accept(partial, step));
        }
    },
    GREEDY_BEST_FIT_ADAPTIVE("Greedy - Best-Fit Adaptive") {
        @Override
        PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                             FrameConsumer onFinishingStep, TerminationCriterion budget) {
            return RectpackAlgorithms.bestFitAdaptiveGreedy(new Random(seed)).run(PackingSolution.empty(boxLength), rectangles,
                    (partial, step, total) -> onStep.accept(partial, step));
        }
    },
    LOCAL_SEARCH_GEOMETRIC("Local Search - Geometric") {
        @Override
        PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                             FrameConsumer onFinishingStep, TerminationCriterion budget) {
            return RectpackAlgorithms.geometricLocalSearch(rectangles, boxLength, new Random(seed))
                    .run(budget, listenerOf(onStep, onFinishingStep));
        }
    },
    LOCAL_SEARCH_PERMUTATION("Local Search - Rule-Based (Permutation)") {
        @Override
        PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                             FrameConsumer onFinishingStep, TerminationCriterion budget) {
            LocalSearchListener<PermutationSolution> listener = new LocalSearchListener<>() {
                @Override
                public void onAccepted(PermutationSolution state, long iteration) {
                    onStep.accept(state.placement(), iteration);
                }

                @Override
                public void onFinishingStep(PermutationSolution state) {
                    onFinishingStep.accept(state.placement(), -1);
                }
            };
            var result = RectpackAlgorithms.permutationLocalSearch(rectangles, boxLength, new Random(seed)).run(budget, listener);
            return result.placement();
        }
    },
    LOCAL_SEARCH_RELAXED_OVERLAP("Local Search - Relaxed Overlap") {
        @Override
        PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                             FrameConsumer onFinishingStep, TerminationCriterion budget) {
            return RectpackAlgorithms.relaxedOverlapLocalSearch(rectangles, boxLength, new Random(seed))
                    .run(budget, listenerOf(onStep, onFinishingStep));
        }
    };

    private final String displayName;

    AlgorithmChoice(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** Shared by every local-search constant whose solution representation IS the {@link
     *  PackingSolution} the frame consumers want directly (unlike permutation local search, which
     *  wraps it in a {@link PermutationSolution} and so builds its own listener above instead). */
    private static LocalSearchListener<PackingSolution> listenerOf(FrameConsumer onStep, FrameConsumer onFinishingStep) {
        return new LocalSearchListener<>() {
            @Override
            public void onAccepted(PackingSolution state, long iteration) {
                onStep.accept(state, iteration);
            }

            @Override
            public void onFinishingStep(PackingSolution state) {
                onFinishingStep.accept(state, -1);
            }
        };
    }

    abstract PackingSolution run(List<Rectangle> rectangles, int boxLength, long seed, FrameConsumer onStep,
                                  FrameConsumer onFinishingStep, TerminationCriterion budget);
}
