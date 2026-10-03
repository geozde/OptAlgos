package optalgos.rectpack.generator;

/**
 * Parameters for {@link InstanceGenerator}, matching the spec exactly: a rectangle count, an
 * independent [min, max] bound per side length, and the box length L (no rectangle side may exceed
 * L, per the spec's own simplifying assumption).
 */
public record InstanceConfig(int rectangleCount, int minWidth, int maxWidth, int minHeight, int maxHeight,
                              int boxLength) {

    public InstanceConfig {
        if (rectangleCount < 0) {
            throw new IllegalArgumentException("rectangleCount must be >= 0: " + rectangleCount);
        }
        if (boxLength <= 0) {
            throw new IllegalArgumentException("boxLength must be positive: " + boxLength);
        }
        requireValidBounds("width", minWidth, maxWidth, boxLength);
        requireValidBounds("height", minHeight, maxHeight, boxLength);
    }

    private static void requireValidBounds(String label, int min, int max, int boxLength) {
        if (min <= 0) {
            throw new IllegalArgumentException("min " + label + " must be positive: " + min);
        }
        if (min > max) {
            throw new IllegalArgumentException("min " + label + " (" + min + ") must be <= max " + label + " (" + max + ")");
        }
        if (max > boxLength) {
            throw new IllegalArgumentException("max " + label + " (" + max + ") must be <= boxLength (" + boxLength
                    + ") - the spec assumes no rectangle side exceeds L");
        }
    }
}
