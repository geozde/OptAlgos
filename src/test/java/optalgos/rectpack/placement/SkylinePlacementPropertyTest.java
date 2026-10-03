package optalgos.rectpack.placement;

import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class SkylinePlacementPropertyTest {

    private final SkylinePlacement placement = new SkylinePlacement();

    @RepeatedTest(50)
    void placesEverythingWithoutOverlapAndWithinBounds() {
        long seed = ThreadLocalRandom.current().nextLong();
        var config = new InstanceConfig(150, 3, 30, 3, 30, 60);
        List<Rectangle> rectangles = new InstanceGenerator(seed).generate(config);

        PackingSolution solution = placement.placeAll(PackingSolution.empty(config.boxLength()), rectangles);

        assertThat(solution.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(solution.hasAnyOverlap()).as("seed=%d", seed).isFalse();
        for (PlacedRectangle placed : solution.allPlacedRectangles()) {
            assertThat(placed.fitsWithinBox(config.boxLength())).as("seed=%d, rect=%s", seed, placed).isTrue();
        }
    }

    @Test
    void usesRotationWhenOnlyTheRotatedOrientationFits() {
        PackingSolution solution = PackingSolution.empty(10);
        solution = placement.place(solution, new Rectangle(1, 10, 7));   // fills the full width, height 7
        solution = placement.place(solution, new Rectangle(2, 1, 5));    // normal needs 12 tall (too much); rotated needs 8

        assertThat(solution.boxCount()).isEqualTo(1);   // both still fit in the same single box
        PlacedRectangle second = solution.allPlacedRectangles().stream()
                .filter(r -> r.rectangle().id() == 2)
                .findFirst().orElseThrow();
        assertThat(second.orientation()).isEqualTo(Orientation.ROTATED);
    }

    @Test
    void opensANewBoxOnlyWhenNothingExistingFits() {
        PackingSolution solution = PackingSolution.empty(10);
        solution = placement.place(solution, new Rectangle(1, 10, 5));
        solution = placement.place(solution, new Rectangle(2, 10, 5));   // together exactly fill the 10x10 box
        assertThat(solution.boxCount()).isEqualTo(1);

        solution = placement.place(solution, new Rectangle(3, 1, 1));    // no room left anywhere in box 0
        assertThat(solution.boxCount()).isEqualTo(2);
    }

    @Test
    void placementCostIsNonNegativeAndZeroForAPerfectFit() {
        PackingSolution solution = PackingSolution.empty(10);
        solution = placement.place(solution, new Rectangle(1, 10, 4));

        double cost = placement.placementCost(solution, new Rectangle(2, 10, 6));   // exactly fills the rest
        assertThat(cost).isEqualTo(0.0);
    }

    @RepeatedTest(20)
    void placementCostNeverGoesNegative() {
        long seed = ThreadLocalRandom.current().nextLong();
        var config = new InstanceConfig(60, 2, 20, 2, 20, 40);
        List<Rectangle> rectangles = new InstanceGenerator(seed).generate(config);
        PackingSolution solution = PackingSolution.empty(config.boxLength());

        for (Rectangle rectangle : rectangles) {
            double cost = placement.placementCost(solution, rectangle);
            assertThat(cost).as("seed=%d", seed).isGreaterThanOrEqualTo(0.0);
            solution = placement.place(solution, rectangle);
        }
    }
}
