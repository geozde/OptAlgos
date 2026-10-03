package optalgos.rectpack.generator;

import optalgos.rectpack.model.Rectangle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates rectangle instances per the spec: {@code rectangleCount} rectangles, each side length
 * drawn uniformly at random within its own [min, max] bound, all random decisions stochastically
 * independent. Seeded for reproducibility - tests, and comparing algorithms fairly on the same
 * instance.
 */
public final class InstanceGenerator {

    private final Random random;

    public InstanceGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** Convenience constructor for callers (e.g. the GUI's "Generate" button) that don't need a
     *  reproducible seed. */
    public InstanceGenerator() {
        this(new Random().nextLong());
    }

    public List<Rectangle> generate(InstanceConfig config) {
        List<Rectangle> rectangles = new ArrayList<>(config.rectangleCount());
        for (int id = 0; id < config.rectangleCount(); id++) {
            int width = uniform(config.minWidth(), config.maxWidth());
            int height = uniform(config.minHeight(), config.maxHeight());
            rectangles.add(new Rectangle(id, width, height));
        }
        return rectangles;
    }

    private int uniform(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
