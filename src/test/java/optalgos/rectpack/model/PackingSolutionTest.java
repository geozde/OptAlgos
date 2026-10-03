package optalgos.rectpack.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PackingSolutionTest {

    @Test
    void onePerBoxPlacesEachRectangleAloneInItsOwnBox() {
        var rectangles = List.of(new Rectangle(1, 3, 3), new Rectangle(2, 4, 4), new Rectangle(3, 2, 2));
        var solution = PackingSolution.onePerBox(rectangles, 10);

        assertThat(solution.boxCount()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(solution.rectanglesIn(i)).hasSize(1);
        }
    }

    private static PlacedRectangle placed(int id, int boxIndex, int x, int y, int w, int h) {
        return new PlacedRectangle(new Rectangle(id, w, h), boxIndex, x, y, Orientation.NORMAL);
    }

    @Test
    void startsEmpty() {
        var solution = PackingSolution.empty(10);
        assertThat(solution.boxCount()).isZero();
        assertThat(solution.boxSlotCount()).isZero();
    }

    @Test
    void placingIntoANewSlotOpensABox() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 4, 4));

        assertThat(solution.boxCount()).isEqualTo(1);
        assertThat(solution.rectanglesIn(0)).hasSize(1);
    }

    @Test
    void emptyingABoxDropsItFromBoxCountButKeepsTheSlot() {
        var solution = PackingSolution.empty(10);
        var a = placed(1, 0, 0, 0, 4, 4);
        var b = placed(2, 1, 0, 0, 4, 4);
        solution.place(a);
        solution.place(b);
        assertThat(solution.boxCount()).isEqualTo(2);

        solution.remove(a);

        assertThat(solution.boxCount()).isEqualTo(1);      // slot 0 is now empty, doesn't count
        assertThat(solution.boxSlotCount()).isEqualTo(2);   // but the slot itself still exists
        assertThat(solution.isEmptyBox(0)).isTrue();
        assertThat(solution.rectanglesIn(1)).containsExactly(b);
    }

    @Test
    void removeByIdFindsAndRemovesRegardlessOfWhichBox() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 4, 4));

        PlacedRectangle removed = solution.removeById(1);

        assertThat(removed).isNotNull();
        assertThat(removed.rectangle().id()).isEqualTo(1);
        assertThat(solution.boxCount()).isZero();
    }

    @Test
    void removeByIdReturnsNullWhenNotPresent() {
        var solution = PackingSolution.empty(10);
        assertThat(solution.removeById(99)).isNull();
    }

    @Test
    void placingOutsideBoxBoundsIsRejected() {
        var solution = PackingSolution.empty(10);
        assertThatThrownBy(() -> solution.place(placed(1, 0, 8, 8, 4, 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placingIntoAnIndexThatSkipsAheadIsRejected() {
        var solution = PackingSolution.empty(10);
        assertThatThrownBy(() -> solution.place(placed(1, 5, 0, 0, 4, 4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void touchingRectanglesAreNotOverlapping() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 4, 4));
        solution.place(placed(2, 0, 4, 0, 4, 4));   // shares the edge x=4, no interior overlap

        assertThat(solution.hasAnyOverlap()).isFalse();
        assertThat(solution.totalOverlapPenalty()).isZero();
    }

    @Test
    void trulyOverlappingRectanglesAreDetected() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 4, 4));
        solution.place(placed(2, 0, 2, 2, 4, 4));   // overlaps in the [2,4]x[2,4] square

        assertThat(solution.hasAnyOverlap()).isTrue();
        assertThat(solution.totalOverlapPenalty()).isGreaterThan(0.0);
    }

    @Test
    void overlapInDifferentBoxesIsIgnored() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 8, 8));
        solution.place(placed(2, 1, 0, 0, 8, 8));   // identical coordinates, but a different box

        assertThat(solution.hasAnyOverlap()).isFalse();
    }

    @Test
    void snapshotIsIndependentOfFurtherMutation() {
        var solution = PackingSolution.empty(10);
        solution.place(placed(1, 0, 0, 0, 4, 4));
        PackingSolution snapshot = solution.snapshot();

        solution.place(placed(2, 1, 0, 0, 4, 4));

        assertThat(snapshot.boxCount()).isEqualTo(1);
        assertThat(solution.boxCount()).isEqualTo(2);
    }

    @Test
    void rotatedRectangleUsesSwappedEffectiveDimensions() {
        var rotated = new PlacedRectangle(new Rectangle(1, 3, 7), 0, 0, 0, Orientation.ROTATED);
        assertThat(rotated.effectiveWidth()).isEqualTo(7);
        assertThat(rotated.effectiveHeight()).isEqualTo(3);
    }
}
