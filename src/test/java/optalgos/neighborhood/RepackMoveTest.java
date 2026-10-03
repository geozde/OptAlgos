package optalgos.neighborhood;

import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepackMoveTest {

    @Test
    void reNestsABoxInPlaceWithoutOpeningANewOne() {
        // The ordering this move depends on: every removal happens before any placement, so the box
        // is momentarily empty and its rectangles land back in the SAME slot. Placing before removing
        // would either collide with the occupants still there or push the contents into a new box.
        Rectangle a = new Rectangle(1, 6, 4);
        Rectangle b = new Rectangle(2, 5, 5);
        PackingSolution solution = PackingSolution.empty(20);
        PlacedRectangle aBefore = new PlacedRectangle(a, 0, 0, 0, Orientation.NORMAL);
        PlacedRectangle bBefore = new PlacedRectangle(b, 0, 0, 10, Orientation.NORMAL);
        solution.place(aBefore);
        solution.place(bBefore);

        PlacedRectangle aAfter = new PlacedRectangle(a, 0, 0, 0, Orientation.NORMAL);
        PlacedRectangle bAfter = new PlacedRectangle(b, 0, 6, 0, Orientation.NORMAL);
        new RepackMove(List.of(aBefore, bBefore), List.of(aAfter, bAfter), -1.0).apply(solution);

        assertThat(solution.boxCount()).isEqualTo(1);
        assertThat(solution.boxSlotCount()).isEqualTo(1);
        assertThat(solution.rectanglesIn(0)).containsExactlyInAnyOrder(aAfter, bAfter);
        assertThat(solution.hasAnyOverlap()).isFalse();
    }

    @Test
    void movingEveryOccupantElsewhereLeavesTheSourceBoxEmpty() {
        // What draining a box comes down to: one move that empties a box and hands its contents to
        // another. The emptied slot must stop counting toward the objective.
        Rectangle a = new Rectangle(1, 5, 5);
        PackingSolution solution = PackingSolution.empty(20);
        PlacedRectangle filler = new PlacedRectangle(new Rectangle(2, 5, 5), 0, 0, 0, Orientation.NORMAL);
        PlacedRectangle before = new PlacedRectangle(a, 1, 0, 0, Orientation.NORMAL);
        solution.place(filler);
        solution.place(before);
        assertThat(solution.boxCount()).isEqualTo(2);

        PlacedRectangle after = new PlacedRectangle(a, 0, 5, 0, Orientation.NORMAL);
        new RepackMove(List.of(before), List.of(after), -1.0).apply(solution);

        assertThat(solution.boxCount()).isEqualTo(1);
        assertThat(solution.isEmptyBox(1)).isTrue();
        assertThat(solution.allPlacedRectangles()).containsExactlyInAnyOrder(filler, after);
    }
}
