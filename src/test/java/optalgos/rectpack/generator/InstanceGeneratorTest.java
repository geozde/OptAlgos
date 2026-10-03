package optalgos.rectpack.generator;

import optalgos.rectpack.model.Rectangle;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

class InstanceGeneratorTest {

    @RepeatedTest(20)
    void respectsPerDimensionBoundsAndCount() {
        long seed = ThreadLocalRandom.current().nextLong();
        var config = new InstanceConfig(200, 3, 17, 5, 40, 100);
        var rectangles = new InstanceGenerator(seed).generate(config);

        assertThat(rectangles).hasSize(200);
        assertThat(rectangles).allSatisfy(r -> {
            assertThat(r.width()).isBetween(3, 17);
            assertThat(r.height()).isBetween(5, 40);
        });
    }

    @Test
    void isDeterministicForAGivenSeed() {
        var config = new InstanceConfig(50, 1, 20, 1, 20, 50);
        List<Rectangle> first = new InstanceGenerator(42L).generate(config);
        List<Rectangle> second = new InstanceGenerator(42L).generate(config);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeedsUsuallyProduceDifferentInstances() {
        var config = new InstanceConfig(50, 1, 20, 1, 20, 50);
        List<Rectangle> first = new InstanceGenerator(1L).generate(config);
        List<Rectangle> second = new InstanceGenerator(2L).generate(config);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void widthAndHeightAreDrawnIndependently() {
        // Guards specifically against a bug where one random draw gets reused for both dimensions
        // (which would force width == height and collapse this count to ~50 instead of hundreds).
        var config = new InstanceConfig(500, 1, 50, 1, 50, 50);
        var rectangles = new InstanceGenerator(7L).generate(config);

        long distinctCombinations = rectangles.stream().map(r -> r.width() + "x" + r.height()).distinct().count();
        assertThat(distinctCombinations).isGreaterThan(100);
    }

    @Test
    void rejectsMaxSideLargerThanBoxLength() {
        assertThatThrownBy(() -> new InstanceConfig(10, 1, 60, 1, 10, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMinGreaterThanMax() {
        assertThatThrownBy(() -> new InstanceConfig(10, 20, 5, 1, 10, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
