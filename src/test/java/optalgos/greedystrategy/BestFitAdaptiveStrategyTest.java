package optalgos.greedystrategy;

import optalgos.algorithm.greedy.Greedy;
import optalgos.algorithm.greedy.GreedyListener;
import optalgos.rectpack.generator.InstanceConfig;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BestFitAdaptiveStrategyTest {

    @Test
    void picksWhicheverRemainingRectangleWastesTheLeastRightNow() {
        var placement = new SkylinePlacement();
        var strategy = new BestFitAdaptiveStrategy(placement, new Random(1L));

        // A 10x4 rectangle fills the bottom of a 10x10 box, leaving a 10x6 strip free above it.
        PackingSolution partial = PackingSolution.empty(10);
        partial = placement.place(partial, new Rectangle(99, 10, 4));

        List<Rectangle> remaining = List.of(
                new Rectangle(1, 9, 9),    // too tall for the remaining strip in either orientation - would need a new box
                new Rectangle(2, 5, 5));   // comfortably fits in the remaining strip, no waste

        Rectangle chosen = strategy.selectNext(partial, remaining);

        assertThat(chosen.id()).isEqualTo(2);
    }

    @Test
    void endToEndRunProducesAValidPacking() {
        var placement = new SkylinePlacement();
        var strategy = new BestFitAdaptiveStrategy(placement, new Random(5L));
        var greedy = new Greedy<Rectangle, PackingSolution>(strategy, placement);

        List<Rectangle> rectangles = new InstanceGenerator(5L).generate(new InstanceConfig(80, 3, 15, 3, 15, 30));

        PackingSolution result = greedy.run(PackingSolution.empty(30), rectangles, GreedyListener.noOp());

        assertThat(result.allPlacedRectangles()).hasSize(rectangles.size());
        assertThat(result.hasAnyOverlap()).isFalse();
        assertThat(result.boxCount()).isLessThan(rectangles.size());   // strictly better than one-per-box
    }
}
