package optalgos.gui;

import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrameHistoryTest {

    private static final int BOX_LENGTH = 20;

    /** A tiny two-rectangle solution, both in {@code box}, at a position derived from {@code offset}
     *  (so two calls with different offsets are guaranteed to differ geometrically, and two calls with
     *  the same arguments are guaranteed to be placement-identical - id, box, x, y and orientation all
     *  match). */
    private static PackingSolution twoRectangles(int box, int offset) {
        // Bounded to a valid in-box x position (rectangle width 3, so [0, BOX_LENGTH - 3]) regardless
        // of how large a raw offset a test passes in - consecutive raw offsets still map to
        // consecutive, distinct positions (mod of consecutive integers by N > 1 never collides), which
        // is all these tests need: something that reliably differs from whatever came immediately
        // before it.
        int x = offset % (BOX_LENGTH - 3);
        PackingSolution solution = PackingSolution.empty(BOX_LENGTH);
        solution.place(new PlacedRectangle(new Rectangle(1, 3, 3), box, x, 0, Orientation.NORMAL));
        solution.place(new PlacedRectangle(new Rectangle(2, 3, 3), box, x, 5, Orientation.NORMAL));
        return solution;
    }

    private static Frame frameFrom(PackingSolution solution, long step) {
        return new Frame(solution.snapshot(), step, false, false);
    }

    /** A solution with exactly {@code boxCount} boxes, one rectangle each - lets a test control
     *  {@link PackingSolution#boxCount()} directly and deterministically, the same quantity {@link
     *  FrameHistory} uses to decide whether a step counts as a genuine improvement. */
    private static PackingSolution withBoxCount(int boxCount) {
        PackingSolution solution = PackingSolution.empty(BOX_LENGTH);
        for (int box = 0; box < boxCount; box++) {
            solution.place(new PlacedRectangle(new Rectangle(box, 3, 3), box, 0, 0, Orientation.NORMAL));
        }
        return solution;
    }

    /** Same box count as {@link #withBoxCount}, but with the last box's rectangle nudged to a
     *  position derived from {@code offset} - a genuinely different placement (so it's eligible for
     *  retention) that does NOT change the box count (so it's never itself an "improvement"), exactly
     *  the shape of a real sparsity-thinning or in-place-rotation move. */
    private static PackingSolution withBoxCountAndJitter(int boxCount, int offset) {
        PackingSolution solution = PackingSolution.empty(BOX_LENGTH);
        int jitterX = offset % (BOX_LENGTH - 3);
        for (int box = 0; box < boxCount; box++) {
            int x = box == boxCount - 1 ? jitterX : 0;
            solution.place(new PlacedRectangle(new Rectangle(box, 3, 3), box, x, 0, Orientation.NORMAL));
        }
        return solution;
    }

    @Test
    void firstOfferedStepIsAlwaysKeptEvenWithNothingToCompareAgainst() {
        var history = new FrameHistory();
        assertThat(history.shouldKeep(twoRectangles(0, 0), 1)).isTrue();
    }

    @Test
    void aStepIdenticalToTheLastKeptFrameIsNeverKept() {
        // The core fix this class exists for: LocalSearch calls the consumer on EVERY iteration,
        // including onPlateau continuations that don't move a single rectangle. Offering the exact
        // same geometric state repeatedly must never produce more than the one kept frame for it.
        var history = new FrameHistory();
        PackingSolution unchanged = twoRectangles(0, 0);
        assertThat(history.shouldKeep(unchanged, 1)).isTrue();
        history.record(frameFrom(unchanged, 1));

        for (long step = 2; step <= 50; step++) {
            assertThat(history.shouldKeep(twoRectangles(0, 0), step))
                    .as("step %d offers a geometrically identical state and must be rejected", step)
                    .isFalse();
        }
        assertThat(history.snapshot()).hasSize(1);
    }

    @Test
    void aGenuineGeometricChangeIsKeptEvenWithoutBoxCountImproving() {
        // Moving a rectangle within/between boxes without closing a box (no improvement in the box-
        // count sense) is still a real, visible change and must be eligible for retention.
        var history = new FrameHistory();
        PackingSolution first = twoRectangles(0, 0);
        assertThat(history.shouldKeep(first, 1)).isTrue();
        history.record(frameFrom(first, 1));

        PackingSolution moved = twoRectangles(0, 10);   // same box count, different position
        assertThat(history.shouldKeep(moved, 2)).isTrue();
    }

    @Test
    void interleavedUnchangedStepsBetweenGenuineMovesKeepOnlyTheGenuineOnes() {
        // Mirrors the exact scenario reported against the real GUI: a handful of real moves, with many
        // onPlateau-style no-op continuations offered in between each one. Every kept frame must
        // differ from the one before it - no run of identical-looking frames should ever appear.
        var history = new FrameHistory();
        long step = 1;
        PackingSolution state = twoRectangles(0, 0);
        assertThat(history.shouldKeep(state, step++)).isTrue();
        history.record(frameFrom(state, step));

        int keptAfterFirst = history.snapshot().size();
        for (int move = 1; move <= 5; move++) {
            for (int noOp = 0; noOp < 20; noOp++) {
                assertThat(history.shouldKeep(state, step++))
                        .as("unchanged repeat before move %d must not be kept", move)
                        .isFalse();
            }
            state = twoRectangles(0, move);   // one genuine move
            assertThat(history.shouldKeep(state, step)).isTrue();
            history.record(frameFrom(state, step++));
        }
        assertThat(history.snapshot()).hasSize(keptAfterFirst + 5);

        // Every consecutive pair of kept frames must be genuinely different from each other.
        var kept = history.snapshot();
        for (int i = 1; i < kept.size(); i++) {
            assertThat(placementOf(kept.get(i - 1).snapshot(), 1))
                    .as("kept frames %d and %d must differ", i - 1, i)
                    .isNotEqualTo(placementOf(kept.get(i).snapshot(), 1));
        }
    }

    private static PlacedRectangle placementOf(PackingSolution solution, int rectangleId) {
        return solution.allPlacedRectangles().stream()
                .filter(p -> p.rectangle().id() == rectangleId)
                .findFirst().orElseThrow();
    }

    @Test
    void neverExceedsTheCapEvenOverManyThousandsOfGenuineChanges() {
        var history = new FrameHistory();
        for (int step = 1; step <= 5_000; step++) {
            PackingSolution state = twoRectangles(0, step);   // genuinely different every single step
            if (history.shouldKeep(state, step)) {
                history.record(frameFrom(state, step));
            }
            assertThat(history.snapshot().size()).isLessThanOrEqualTo(FrameHistory.MAX_FRAMES);
        }
    }

    @Test
    void compactionNeverDiscardsABoxCountImprovementEvenWhenFillerStepsVastlyOutnumberThem() {
        // Mirrors the exact scenario reported against GeometricNeighborhood: many genuinely-different
        // moves that never change the box count (sparsity thinning, in-place rotation nudges) for
        // every one that actually closes a box. Every one of the 20 box-closing steps below must still
        // have its own frame in the final, decimated set - none of them may be thinned away just
        // because they're vastly outnumbered by the filler steps around them.
        var history = new FrameHistory();
        long step = 1;
        for (int boxCount = 20; boxCount >= 1; boxCount--) {
            PackingSolution improved = withBoxCount(boxCount);
            assertThat(history.shouldKeep(improved, step))
                    .as("box count %d is a new best and must be kept", boxCount)
                    .isTrue();
            history.record(frameFrom(improved, step++));

            for (int filler = 0; filler < 50; filler++) {
                PackingSolution jittered = withBoxCountAndJitter(boxCount, filler);
                if (history.shouldKeep(jittered, step)) {
                    history.record(frameFrom(jittered, step));
                }
                step++;
            }
        }

        List<Integer> boxCountsShown = history.snapshot().stream()
                .map(f -> f.snapshot().boxCount())
                .toList();
        for (int boxCount = 20; boxCount >= 1; boxCount--) {
            assertThat(boxCountsShown)
                    .as("box count %d must still have its own frame after compaction", boxCount)
                    .contains(boxCount);
        }
        assertThat(history.snapshot().size()).isLessThanOrEqualTo(FrameHistory.MAX_FRAMES);
    }

    @Test
    void recordFinalIdenticalToLastKeptReplacesRatherThanDuplicatingIt() {
        // A real gap the unconditional-append version of recordFinal had: when a search plateaus with
        // nothing left to try, the last live-recorded state and the algorithm's actual returned result
        // are often the exact same placement (finish() is frequently a no-op) - appending both produced
        // two consecutive, indistinguishable frames, exactly the problem shouldKeep exists to prevent
        // everywhere else.
        var history = new FrameHistory();
        history.record(frameFrom(twoRectangles(0, 0), 1));
        assertThat(history.snapshot()).hasSize(1);

        Frame finalFrame = frameFrom(twoRectangles(0, 0), -1);
        history.recordFinal(finalFrame);

        var kept = history.snapshot();
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0)).isSameAs(finalFrame);
    }

    @Test
    void recordFinalIsAlwaysTheLastFrameAndNeverPushesPastTheCap() {
        var history = new FrameHistory();
        for (int step = 1; step <= 10_000; step++) {
            PackingSolution state = twoRectangles(0, step);
            if (history.shouldKeep(state, step)) {
                history.record(frameFrom(state, step));
            }
        }
        Frame finalFrame = frameFrom(twoRectangles(0, 99_999), -1);
        history.recordFinal(finalFrame);

        var kept = history.snapshot();
        assertThat(kept.size()).isLessThanOrEqualTo(FrameHistory.MAX_FRAMES);
        assertThat(kept.get(kept.size() - 1)).isSameAs(finalFrame);
    }

    @Test
    void recordFinalOnAShortRunIsStillLastAndNothingIsAddedAfterIt() {
        var history = new FrameHistory();
        PackingSolution first = twoRectangles(0, 0);
        history.record(frameFrom(first, 1));
        Frame finalFrame = frameFrom(twoRectangles(0, 1), -1);
        history.recordFinal(finalFrame);
        history.record(frameFrom(twoRectangles(0, 2), 2));   // must be a no-op: nothing follows the final frame

        var kept = history.snapshot();
        assertThat(kept.get(kept.size() - 1)).isSameAs(finalFrame);
    }

    @Test
    void snapshotReflectsGrowthAsGenuineChangesAreRecorded() {
        var history = new FrameHistory();
        assertThat(history.snapshot()).isEmpty();
        history.record(frameFrom(twoRectangles(0, 0), 1));
        assertThat(history.snapshot()).hasSize(1);
        history.record(frameFrom(twoRectangles(0, 1), 2));
        assertThat(history.snapshot()).hasSize(2);
    }
}
