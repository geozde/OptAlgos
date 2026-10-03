package optalgos.rectpack.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class BoxViewTest {

    private static PlacedRectangle placed(int id, int x, int y, int w, int h) {
        return new PlacedRectangle(new Rectangle(id, w, h), 0, x, y, Orientation.NORMAL);
    }

    private static BoxView box(PlacedRectangle... rectangles) {
        return new BoxView(0, List.of(rectangles));
    }

    @Test
    void reportsNoOverlapForDisjointRectangles() {
        var overlap = box(placed(1, 0, 0, 4, 4), placed(2, 4, 0, 4, 4)).overlap();

        assertThat(overlap.isPresent()).isFalse();
        assertThat(overlap.pairCount()).isZero();
        assertThat(overlap.worstPercent()).isZero();
    }

    @Test
    void touchingRectanglesAreNotOverlapping() {
        // Openly disjoint per the spec: a shared edge is allowed, so a box of flush-packed rectangles
        // must not be annotated as carrying overlap.
        var overlap = box(placed(1, 0, 0, 4, 4), placed(2, 0, 4, 4, 4)).overlap();

        assertThat(overlap.isPresent()).isFalse();
    }

    @Test
    void reportsTheWorstPairAsAPercentageOfTheLargerArea() {
        // 4x4 and 8x8 sharing a 2x4 strip: 8 / max(16, 64) = 12.5%, per the spec's own definition.
        var overlap = box(placed(1, 0, 0, 4, 4), placed(2, 2, 0, 8, 8)).overlap();

        assertThat(overlap.pairCount()).isEqualTo(1);
        assertThat(overlap.worstRatio()).isEqualTo(8 / 64.0);
        assertThat(overlap.worstPercent()).isEqualTo(13);
    }

    @Test
    void fullyCoincidentRectanglesOverlapCompletely() {
        var overlap = box(placed(1, 0, 0, 5, 5), placed(2, 0, 0, 5, 5)).overlap();

        assertThat(overlap.worstPercent()).isEqualTo(100);
    }

    @Test
    void countsEveryOverlappingPairButReportsOnlyTheWorstRatio() {
        // Three mutually overlapping rectangles: three pairs, and the reported figure is the largest
        // of the three ratios - never their sum, which is what the search objective uses and what
        // would make the displayed figure exceed 100%.
        var overlap = box(placed(1, 0, 0, 4, 4), placed(2, 1, 1, 4, 4), placed(3, 0, 0, 4, 4)).overlap();

        assertThat(overlap.pairCount()).isEqualTo(3);
        assertThat(overlap.worstPercent()).isEqualTo(100);
        assertThat(overlap.worstRatio()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void aSingleRectangleCannotOverlapAnything() {
        assertThat(box(placed(1, 0, 0, 4, 4)).overlap().isPresent()).isFalse();
        assertThat(box().overlap()).isEqualTo(BoxOverlap.NONE);
    }
}
