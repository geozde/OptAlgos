package optalgos.benchmark;

import optalgos.rectpack.generator.InstanceConfig;

/**
 * One parametrized case for the benchmark harness, matching the spec's tuple exactly: generate
 * {@code numInstances} random instances of this shape, and run every algorithm configuration
 * against each.
 */
public record BenchmarkCase(int numInstances, int numRectangles, int minWidth, int maxWidth,
                             int minHeight, int maxHeight, int boxLength) {

    public BenchmarkCase {
        if (numInstances < 0) {
            throw new IllegalArgumentException("numInstances must be >= 0: " + numInstances);
        }
    }

    public InstanceConfig toInstanceConfig() {
        return new InstanceConfig(numRectangles, minWidth, maxWidth, minHeight, maxHeight, boxLength);
    }
}
