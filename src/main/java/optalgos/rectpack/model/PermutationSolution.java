package optalgos.rectpack.model;

import optalgos.algorithm.Measurable;

/**
 * A permutation bundled with its materialized placement (the result of running a placement rule over
 * {@code order}, in order). {@code placement} is what {@code boxCount()} and overlap queries actually
 * run against; {@code order} is what the permutation neighborhood's moves modify.
 */
public record PermutationSolution(RectanglePermutation order, PackingSolution placement) implements Measurable {

    @Override
    public double trueObjective() {
        return placement.boxCount();
    }
}
