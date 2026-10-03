package optalgos.greedystrategy;

import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecreasingAreaStrategyTest {

    @Test
    void alwaysPicksTheLargestRemainingRectangleByArea() {
        var strategy = new DecreasingAreaStrategy();
        List<Rectangle> remaining = List.of(
                new Rectangle(1, 2, 2),     // area 4
                new Rectangle(2, 10, 10),   // area 100
                new Rectangle(3, 5, 5));    // area 25

        Rectangle chosen = strategy.selectNext(PackingSolution.empty(20), remaining);

        assertThat(chosen.id()).isEqualTo(2);
    }

    @Test
    void ignoresThePartialSolutionEntirely() {
        var strategy = new DecreasingAreaStrategy();
        List<Rectangle> remaining = List.of(new Rectangle(1, 3, 3), new Rectangle(2, 9, 9));

        Rectangle chosenFromEmpty = strategy.selectNext(PackingSolution.empty(20), remaining);

        var busySolution = PackingSolution.empty(20);
        busySolution.place(new PlacedRectangle(new Rectangle(99, 20, 20), 0, 0, 0, Orientation.NORMAL));
        Rectangle chosenFromBusy = strategy.selectNext(busySolution, remaining);

        assertThat(chosenFromEmpty).isEqualTo(chosenFromBusy);
    }
}
